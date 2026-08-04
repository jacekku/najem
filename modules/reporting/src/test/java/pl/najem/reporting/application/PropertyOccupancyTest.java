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
 * "How many units rented / available / unavailable", per property, as of a date.
 * <p>
 * The property is built with one unit in each state so that a single wrong branch shows up as a
 * specific count being off by one rather than as a vague total mismatch.
 */
@Testcontainers
class PropertyOccupancyTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static ProjectionRunner runner;
    static PropertyOccupancy occupancy;

    static UUID workspace;
    static UUID propertyId;
    static UUID occupiedUnit;
    static UUID occupiedButClosedUnit;
    static UUID availableUnit;
    static UUID renovatingUnit;
    static UUID neverListedUnit;

    @BeforeAll
    static void buildAPropertyWithOneUnitInEachState() {
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
        propertyId = portfolio.createProperty(workspace, "ul. Rynek 1, Poznań",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));

        occupiedUnit = portfolio.addUnit(propertyId, "m. 1", new BigDecimal("2000"));
        portfolio.openUnitToRent(occupiedUnit, "ready");
        tenancies.reserve(reserve(occupiedUnit, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));

        // A tenant in residence while the flat is closed to new lettings. Occupied, not unavailable.
        occupiedButClosedUnit = portfolio.addUnit(propertyId, "m. 2", new BigDecimal("2000"));
        portfolio.openUnitToRent(occupiedButClosedUnit, "ready");
        tenancies.reserve(reserve(occupiedButClosedUnit, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        portfolio.closeUnitToRent(occupiedButClosedUnit, "sale planned");

        availableUnit = portfolio.addUnit(propertyId, "m. 3", new BigDecimal("2000"));
        portfolio.openUnitToRent(availableUnit, "ready");

        renovatingUnit = portfolio.addUnit(propertyId, "m. 4", new BigDecimal("2000"));
        portfolio.openUnitToRent(renovatingUnit, "ready");
        portfolio.closeUnitToRent(renovatingUnit, "renovation");

        neverListedUnit = portfolio.addUnit(propertyId, "m. 5", new BigDecimal("2000"));

        // No removed unit here: PM registers UnitRemovedFromProperty but exposes no command that
        // emits it, so there is no way to drive one. The projection handles the event because the
        // event exists; the `not removed` filter is deliberately untested rather than tested
        // against a hand-written row that would only assert Reporting agrees with itself.

        occupancy = new PropertyOccupancy(jdbc);
        runner = new ProjectionRunner(new EventFeed(jdbc, json), jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            List.of(new PropertyProjection(jdbc), new UnitTimelineProjection(jdbc)), 100);
        runner.runOnce();
    }

    private static ReserveTenancy reserve(UUID unitId, LocalDate start, LocalDate end) {
        return new ReserveTenancy(null, null, unitId, List.of(UUID.randomUUID()), List.of(),
            start, new Term.FixedTerm(end), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2000"), null), 10, null, "NAJEM-P-" + unitId);
    }

    @Test
    void countsEachUnitOnceInTheStateItIsActuallyIn() {
        var counts = occupancy.countsFor(workspace, propertyId, LocalDate.of(2026, 6, 1));

        assertThat(counts.occupied()).isEqualTo(2);
        assertThat(counts.available()).isEqualTo(1);
        assertThat(counts.unavailable()).isEqualTo(1);
        assertThat(counts.inventory()).isEqualTo(1);
        assertThat(counts.total()).isEqualTo(5);
    }

    /** A tenant in residence is occupancy, whatever the board says about lettability. */
    @Test
    void countsATenantedUnitAsOccupiedEvenWhenItIsClosedToNewLettings() {
        var counts = occupancy.countsFor(workspace, propertyId, LocalDate.of(2026, 6, 1));

        assertThat(counts.occupied()).isEqualTo(2);
        assertThat(counts.unavailable())
            .as("only the renovating unit is unavailable; the tenanted-and-closed one is occupied")
            .isEqualTo(1);
    }

    /** Occupancy is a question about a date, which is why nothing here is stored. */
    @Test
    void answersDifferentlyForADateBeforeAndAfterTheTenancies() {
        var beforeAnyoneMovedIn = occupancy.countsFor(workspace, propertyId, LocalDate.of(2025, 6, 1));
        var duringTheTenancies = occupancy.countsFor(workspace, propertyId, LocalDate.of(2026, 6, 1));
        var afterEveryoneLeft = occupancy.countsFor(workspace, propertyId, LocalDate.of(2028, 6, 1));

        assertThat(beforeAnyoneMovedIn.occupied()).isZero();
        assertThat(duringTheTenancies.occupied()).isEqualTo(2);
        assertThat(afterEveryoneLeft.occupied()).isZero();
    }

    /**
     * Pins the documented limit rather than pretending it isn't there: {@code asOf} moves occupancy
     * but NOT market state, which is never historised. On a date before anyone moved in, m. 2 was
     * open to rent — yet it counts as unavailable, because it is closed today. The occupied count
     * is genuinely as-of; the available/unavailable split is genuinely current.
     */
    @Test
    void appliesAsOfToOccupancyOnlyAndReportsMarketStateAsItIsNow() {
        var longBeforeAnythingWasClosed = occupancy.countsFor(workspace, propertyId, LocalDate.of(2025, 6, 1));

        assertThat(longBeforeAnythingWasClosed.occupied()).isZero();
        assertThat(longBeforeAnythingWasClosed.available())
            .as("m. 1 and m. 3 — NOT m. 2, which was open then but is closed now")
            .isEqualTo(2);
        assertThat(longBeforeAnythingWasClosed.unavailable())
            .as("m. 2 and m. 4, both closed today, whatever they were on that date")
            .isEqualTo(2);
    }

    @Test
    void countsNothingForAPropertyInAnotherWorkspace() {
        var counts = occupancy.countsFor(UUID.randomUUID(), propertyId, LocalDate.of(2026, 6, 1));

        assertThat(counts.total()).isZero();
    }

    @Test
    void listsThePropertiesAWorkspaceOwns() {
        assertThat(occupancy.propertiesIn(workspace)).containsExactly(propertyId);
        assertThat(occupancy.propertiesIn(UUID.randomUUID())).isEmpty();
    }

    @Test
    void rebuildingReproducesTheIdenticalCounts() {
        var before = occupancy.countsFor(workspace, propertyId, LocalDate.of(2026, 6, 1));

        runner.rebuild(PropertyProjection.NAME);
        runner.rebuild(UnitTimelineProjection.NAME);

        assertThat(occupancy.countsFor(workspace, propertyId, LocalDate.of(2026, 6, 1))).isEqualTo(before);
        assertThat(occupancy.propertiesIn(workspace)).containsExactly(propertyId);
    }
}
