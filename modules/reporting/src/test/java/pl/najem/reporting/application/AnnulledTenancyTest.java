package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.application.ProcessDueStore;
import pl.najem.pm.application.TenancyService;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Term;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision 5: an annulled tenancy is excluded from occupancy and kept on the unit's timeline.
 * <p>
 * The distinction is not cosmetic. A mistaken activation that was taken back genuinely happened to
 * that unit — a manager saw it on the calendar and needs to see it go — but nobody ever lived
 * there, so counting it would report the flat as let for a period it stood empty.
 */
@Testcontainers
class AnnulledTenancyTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static ProjectionRunner runner;
    static UnitOccupancy occupancy;
    static PropertyOccupancy propertyOccupancy;

    static UUID workspace;
    static UUID propertyId;
    static UUID annulledUnit;
    static UUID endedUnit;
    static UUID annulledTenancy;
    static UUID endedTenancy;

    @BeforeAll
    static void twoTenanciesOneAnnulledOneGenuinelyEnded() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm", "classpath:db/reporting")
            .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, json, registry);
        var portfolio = new PortfolioService(store, jdbc);
        var tenancies = new TenancyService(store, jdbc, new ProcessDueStore(jdbc));

        workspace = UUID.randomUUID();
        propertyId = portfolio.createProperty(workspace, "ul. Omyłkowa 2, Gdynia",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));

        annulledUnit = portfolio.addUnit(propertyId, "m. 1", new BigDecimal("2000"));
        portfolio.openUnitToRent(annulledUnit, "ready");
        annulledTenancy = tenancies.reserve(reserve(annulledUnit)).tenancyId();
        tenancies.activate(annulledTenancy, LocalDate.of(2026, 1, 1));
        tenancies.end(annulledTenancy, new EndTenancy(LocalDate.of(2026, 1, 5), null,
            EndReason.ERROR_ANNULLED, "activated the wrong flat", true));

        endedUnit = portfolio.addUnit(propertyId, "m. 2", new BigDecimal("2000"));
        portfolio.openUnitToRent(endedUnit, "ready");
        endedTenancy = tenancies.reserve(reserve(endedUnit)).tenancyId();
        tenancies.activate(endedTenancy, LocalDate.of(2026, 1, 1));
        tenancies.end(endedTenancy, new EndTenancy(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 30),
            EndReason.TENANT_NOTICE, "moved out", true));

        occupancy = new UnitOccupancy(jdbc);
        propertyOccupancy = new PropertyOccupancy(jdbc);
        runner = new ProjectionRunner(new EventFeed(jdbc, json), jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            List.of(new PropertyProjection(jdbc), new UnitTimelineProjection(jdbc)), 100);
        runner.runOnce();
    }

    private static ReserveTenancy reserve(UUID unitId) {
        return new ReserveTenancy(null, null, unitId, List.of(UUID.randomUUID()), List.of(),
            LocalDate.of(2026, 1, 1), new Term.FixedTerm(LocalDate.of(2027, 1, 1)), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2000"), null), 10, null, "NAJEM-A-" + unitId);
    }

    @Test
    void keepsTheAnnulmentOnTheUnitsTimelineRatherThanErasingIt() {
        assertThat(jdbc.queryForList("""
            select kind from reporting_timeline_entry
            where level = 'unit' and subject_id = ? order by global_seq
            """, String.class, annulledUnit))
            .contains("tenancy-annulled")
            .doesNotContain("tenancy-ended");
    }

    @Test
    void tellsAGenuineEndingApartFromAnAnnulment() {
        assertThat(jdbc.queryForList("""
            select kind from reporting_timeline_entry
            where level = 'unit' and subject_id = ? order by global_seq
            """, String.class, endedUnit))
            .contains("tenancy-ended")
            .doesNotContain("tenancy-annulled");
    }

    /** The point of the whole distinction: nobody ever lived there, so it was never occupied. */
    @Test
    void countsNoOccupancyForATenancyThatShouldNeverHaveExisted() {
        var spans = occupancy.spansFor(workspace, annulledUnit, LocalDate.of(2026, 3, 1));

        assertThat(spans).isEmpty();
        assertThat(occupancy.occupantOn(workspace, annulledUnit, LocalDate.of(2026, 1, 3))).isNull();
    }

    /** A real tenancy that ended still occupied the unit while it ran — the two must not be merged. */
    @Test
    void stillCountsOccupancyForATenancyThatGenuinelyEnded() {
        assertThat(occupancy.occupantOn(workspace, endedUnit, LocalDate.of(2026, 3, 1)))
            .isEqualTo(endedTenancy);
    }

    /**
     * Asserts the marker itself, because occupancy alone does not exercise it.
     * <p>
     * A mutation revealed this: removing {@code not annulled} from the occupancy queries changes
     * nothing today, since PM's {@code end()} also releases the unit's period, and a released
     * period with no {@code ended_on} is already excluded. The flag is therefore redundant for
     * occupancy <em>at present</em> and kept for two reasons — it records WHY the period does not
     * count, which the release alone does not, and it holds if PM ever stops releasing on end.
     * Stating that here rather than leaving a line no test can move.
     */
    @Test
    void marksTheAnnulledPeriodAsAnnulledRatherThanMerelyReleased() {
        assertThat(jdbc.queryForObject("""
            select annulled from reporting_unit_period where tenancy_id = ?
            """, Boolean.class, annulledTenancy)).isTrue();
        assertThat(jdbc.queryForObject("""
            select ended_on from reporting_unit_period where tenancy_id = ?
            """, LocalDate.class, annulledTenancy))
            .as("an annulment is not an ending, so it records no end date")
            .isNull();

        assertThat(jdbc.queryForObject("""
            select annulled from reporting_unit_period where tenancy_id = ?
            """, Boolean.class, endedTenancy)).isFalse();
        assertThat(jdbc.queryForObject("""
            select ended_on from reporting_unit_period where tenancy_id = ?
            """, LocalDate.class, endedTenancy)).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void leavesTheAnnulledUnitOutOfThePropertysOccupiedCount() {
        var counts = propertyOccupancy.countsFor(workspace, propertyId, LocalDate.of(2026, 3, 1));

        assertThat(counts.occupied()).as("only m. 2 was ever really let").isEqualTo(1);
        assertThat(counts.total()).isEqualTo(2);
    }

    @Test
    void rebuildingKeepsTheAnnulmentRatherThanReplayingItAsOccupancy() {
        runner.rebuild(UnitTimelineProjection.NAME);

        assertThat(occupancy.spansFor(workspace, annulledUnit, LocalDate.of(2026, 3, 1))).isEmpty();
        assertThat(jdbc.queryForList("""
            select kind from reporting_timeline_entry where level = 'unit' and subject_id = ?
            """, String.class, annulledUnit)).contains("tenancy-annulled");
    }

    /**
     * The tripwire for the annulment spelling, and the reason the projection is not tolerant of two.
     * <p>
     * Reporting reads the stored payload, where Jackson renders the enum as {@code ERROR_ANNULLED}.
     * {@code EndReason.wireName()} — the form PM's javadoc calls the published contract — is
     * {@code error-annulled} and appears only on the integration event. A reader accepting both
     * would keep working if that ever changed, and nobody would learn the contract had moved.
     * <p>
     * So this asserts the exact string. If PM makes {@code wireName()} real on the stored event,
     * <b>this test fails and names what to change</b> — at build time, in the module that depends
     * on it, instead of every annulment silently becoming an ending in production.
     */
    @Test
    void pinsTheExactSpellingOfTheAnnulmentReasonOnTheStoredEvent() {
        var reason = jdbc.queryForObject("""
            select payload ->> 'reasonType' from events
            where event_type = 'TenancyEnded' and payload ->> 'tenancyId' = ?
            """, String.class, annulledTenancy.toString());

        assertThat(reason)
            .as("""
                The stored TenancyEnded no longer spells the annulment reason 'ERROR_ANNULLED'. \
                Reporting excludes annulled tenancies from occupancy by matching this exact string, \
                so a change here turns every annulment into an ordinary ending. Talk to @najem-pm, \
                then update UnitTimelineProjection.ANNULLED.""")
            .isEqualTo("ERROR_ANNULLED");
    }
}
