package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
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
 * A unit's history and current state, driven through PM's real services.
 * <p>
 * The interesting assertions are the two that are not events: a vacant span between tenancies, and
 * a released reservation that must appear on the timeline while not counting as occupancy.
 */
@Testcontainers
@Tag("integration")
class UnitTimelineProjectionTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static ProjectionRunner runner;
    static UnitOccupancy occupancy;

    static UUID workspace;
    static UUID unitId;
    static UUID firstTenancy;
    static UUID secondTenancy;
    static UUID cancelledTenancy;
    static UUID neverOpenedUnitId;

    @BeforeAll
    static void buildAUnitsHistory() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm", "classpath:db/acc", "classpath:db/reporting")
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
        var propertyId = portfolio.createProperty(workspace, "ul. Długa 7, Wrocław",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        unitId = portfolio.addUnit(propertyId, "m. 12", new BigDecimal("2000"));
        portfolio.openUnitToRent(unitId, "ready to let");
        portfolio.setUnitBaseRent(unitId, new BigDecimal("2200"));

        // Two tenancies with a deliberate three-month gap between them, and a cancelled one.
        firstTenancy = tenancies.reserve(reserve(unitId,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1))).tenancyId();
        secondTenancy = tenancies.reserve(reserve(unitId,
            LocalDate.of(2026, 10, 1), LocalDate.of(2027, 10, 1))).tenancyId();
        cancelledTenancy = tenancies.reserve(reserve(unitId,
            LocalDate.of(2028, 1, 1), LocalDate.of(2029, 1, 1))).tenancyId();
        tenancies.cancelReservation(cancelledTenancy, "tenant withdrew");

        // A unit nobody has ever put on the market, to prove 'inventory' is a real third state.
        neverOpenedUnitId = portfolio.addUnit(propertyId, "m. 13", new BigDecimal("1900"));

        portfolio.closeUnitToRent(unitId, "renovation");

        occupancy = new UnitOccupancy(jdbc);
        runner = new ProjectionRunner(new EventFeed(jdbc, json), jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            List.of(new UnitTimelineProjection(jdbc)), 100);
        runner.runOnce();
    }

    private static ReserveTenancy reserve(UUID unitId, LocalDate start, LocalDate end) {
        return new ReserveTenancy(null, null, unitId, List.of(UUID.randomUUID()), List.of(),
            start, new Term.FixedTerm(end), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2200"), null), 10, null, "NAJEM-U-" + start);
    }

    @Test
    void tracksTheUnitsCurrentStateForABoardToRender() {
        var state = jdbc.queryForMap("select * from reporting_unit_state where unit_id = ?", unitId);

        assertThat(state.get("workspace_id")).isEqualTo(workspace);
        assertThat(state.get("name")).isEqualTo("m. 12");
        assertThat(state.get("market_state")).isEqualTo("closed");
        assertThat(state.get("removed")).isEqualTo(false);
        assertThat((BigDecimal) state.get("base_rent")).isEqualByComparingTo("2200");
    }

    /**
     * A unit nobody has opened yet is not "closed" — it was never on the market at all, and a board
     * that shows the two identically tells a manager a renovation is under way when none is.
     */
    @Test
    void distinguishesNeverOpenedFromDeliberatelyClosed() {
        assertThat(jdbc.queryForObject(
            "select market_state from reporting_unit_state where unit_id = ?", String.class, neverOpenedUnitId))
            .isEqualTo("inventory");
        assertThat(jdbc.queryForObject(
            "select market_state from reporting_unit_state where unit_id = ?", String.class, unitId))
            .isEqualTo("closed");
    }

    @Test
    void tellsTheUnitsStoryIncludingTheReservationThatWasCancelled() {
        var kinds = jdbc.queryForList("""
            select kind from reporting_timeline_entry
            where level = 'unit' and subject_id = ? order by global_seq
            """, String.class, unitId);

        assertThat(kinds).containsExactly(
            "unit-added",
            "opened-to-rent",
            "base-rent-set",
            "tenancy-period-registered",
            "tenancy-period-registered",
            "tenancy-period-registered",
            "tenancy-period-released",
            "closed-to-rent");
    }

    /**
     * The assertion that is not an event. Nothing emits "the unit went empty" — the gap between
     * 1 Jul and 1 Oct exists only as the absence of a period, and is computed at read time.
     */
    @Test
    void reportsTheGapBetweenTwoTenanciesAsAVacantSpan() {
        var spans = occupancy.spansFor(workspace, unitId, LocalDate.of(2027, 1, 1));

        assertThat(spans).extracting(UnitOccupancy.Span::state)
            .containsExactly("occupied", "vacant", "occupied");
        var vacancy = spans.get(1);
        assertThat(vacancy.from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(vacancy.to()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(vacancy.days(LocalDate.of(2027, 1, 1))).isEqualTo(92);
    }

    /** A cancelled reservation never occupied the unit — counting it would overstate occupancy. */
    @Test
    void doesNotCountAReleasedReservationAsOccupancy() {
        var spans = occupancy.spansFor(workspace, unitId, LocalDate.of(2029, 6, 1));

        assertThat(spans).extracting(UnitOccupancy.Span::tenancyId).doesNotContain(cancelledTenancy);
        assertThat(jdbc.queryForList("""
            select kind from reporting_timeline_entry where level = 'unit' and subject_id = ?
            """, String.class, unitId))
            .as("but it stays on the timeline: it really was on this unit's calendar")
            .contains("tenancy-period-released");
    }

    @Test
    void namesWhoOccupiesTheUnitNowAndWhoIsComingNext() {
        assertThat(occupancy.occupantOn(workspace, unitId, LocalDate.of(2026, 3, 1)))
            .isEqualTo(firstTenancy);
        assertThat(occupancy.occupantOn(workspace, unitId, LocalDate.of(2026, 8, 1)))
            .as("in the vacant gap nobody occupies it")
            .isNull();
        assertThat(occupancy.nextOccupantAfter(workspace, unitId, LocalDate.of(2026, 8, 1)))
            .isEqualTo(secondTenancy);
    }

    @Test
    void seesNothingOfAUnitInAnotherWorkspace() {
        assertThat(occupancy.spansFor(UUID.randomUUID(), unitId, LocalDate.of(2027, 1, 1))).isEmpty();
        assertThat(occupancy.occupantOn(UUID.randomUUID(), unitId, LocalDate.of(2026, 3, 1))).isNull();
    }

    @Test
    void rebuildingReproducesTheIdenticalHistoryAndState() {
        var kindsBefore = jdbc.queryForList("""
            select kind from reporting_timeline_entry
            where level = 'unit' and subject_id = ? order by global_seq
            """, String.class, unitId);
        var spansBefore = occupancy.spansFor(workspace, unitId, LocalDate.of(2027, 1, 1));

        runner.rebuild(UnitTimelineProjection.NAME);

        assertThat(jdbc.queryForList("""
            select kind from reporting_timeline_entry
            where level = 'unit' and subject_id = ? order by global_seq
            """, String.class, unitId)).isEqualTo(kindsBefore);
        assertThat(occupancy.spansFor(workspace, unitId, LocalDate.of(2027, 1, 1))).isEqualTo(spansBefore);
        assertThat(jdbc.queryForObject(
            "select market_state from reporting_unit_state where unit_id = ?", String.class, unitId))
            .isEqualTo("closed");
    }
}
