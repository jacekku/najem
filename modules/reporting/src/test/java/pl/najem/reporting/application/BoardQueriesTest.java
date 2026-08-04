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
 * Both board queries — the portfolio list and the unit list — over ONE fixture, driven through PM's
 * real services so the projections are fed by the events PM actually emits.
 * <p>
 * <b>Shared deliberately, not merely to save a container.</b> {@code PropertyBoardQuery} and
 * {@code UnitBoardQuery} answer the same question at two levels, and the defect @najem-pm found at
 * najem-build seq 357 was precisely that they had drifted into two answers — the property queries
 * counted a completed tenancy and the unit board erased it. Two test classes with two fixtures
 * would have let that survive; against one fixture, a disagreement is a failing assertion.
 * <p>
 * <b>{@code UnitBoardQuery} had no test whatsoever before this.</b> That is why a predicate serving
 * the prototype's main screen stayed wrong for hours while I waited on a contract change that had
 * already landed — nothing could have told me.
 */
@Testcontainers
class BoardQueriesTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static PropertyBoardQuery board;
    static PropertyOccupancy occupancy;
    static UnitBoardQuery units;

    static UUID workspace;
    static UUID otherWorkspace;
    static UUID letProperty;
    static UUID emptyProperty;
    static UUID pastProperty;
    static UUID pastUnit;
    static UUID endedTenancy;
    static UUID currentTenancy;
    static UUID occupiedUnit;

    @BeforeAll
    static void aPortfolio() {
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
        otherWorkspace = UUID.randomUUID();

        // One property: a let flat, a flat on the market, and a flat never advertised.
        letProperty = portfolio.createProperty(workspace, "ul. Portfelowa 1, Gdańsk",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));

        occupiedUnit = portfolio.addUnit(letProperty, "m. 1", new BigDecimal("2000"));
        portfolio.openUnitToRent(occupiedUnit, "ready");
        currentTenancy = tenancies.reserve(reserve(occupiedUnit)).tenancyId();
        tenancies.activate(currentTenancy, LocalDate.of(2026, 1, 1));

        var onTheMarket = portfolio.addUnit(letProperty, "m. 2", new BigDecimal("2000"));
        portfolio.openUnitToRent(onTheMarket, "ready");

        portfolio.addUnit(letProperty, "m. 3", new BigDecimal("2000"));

        // A property whose only tenancy ran for six months and then ended.
        pastProperty = portfolio.createProperty(workspace, "ul. Historyczna 2, Gdańsk",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        pastUnit = portfolio.addUnit(pastProperty, "m. 1", new BigDecimal("2000"));
        portfolio.openUnitToRent(pastUnit, "ready");
        endedTenancy = tenancies.reserve(reserve(pastUnit)).tenancyId();
        tenancies.activate(endedTenancy, LocalDate.of(2026, 1, 1));
        tenancies.end(endedTenancy, new EndTenancy(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 30),
            EndReason.TENANT_NOTICE, "moved out", true));

        // A property with nothing in it yet.
        emptyProperty = portfolio.createProperty(workspace, "ul. Pusta 3, Gdańsk",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));

        // Another agency's portfolio entirely.
        portfolio.createProperty(otherWorkspace, "ul. Cudza 9, Sopot",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));

        board = new PropertyBoardQuery(jdbc);
        occupancy = new PropertyOccupancy(jdbc);
        units = new UnitBoardQuery(jdbc);
        new ProjectionRunner(new EventFeed(jdbc, json), jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            List.of(new PropertyProjection(jdbc), new UnitTimelineProjection(jdbc)), 500).runOnce();
    }

    private static ReserveTenancy reserve(UUID unitId) {
        return new ReserveTenancy(null, null, unitId, List.of(UUID.randomUUID()), List.of(),
            LocalDate.of(2026, 1, 1), new Term.FixedTerm(LocalDate.of(2027, 1, 1)), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2000"), null), 10, null, "NAJEM-PB-" + unitId);
    }

    @Test
    void listsEveryPropertyInTheWorkspaceOrderedByAddress() {
        var rows = board.forWorkspace(workspace, LocalDate.of(2026, 3, 1));

        assertThat(rows).extracting(PropertyBoardQuery.Row::address)
            .containsExactly("ul. Historyczna 2, Gdańsk", "ul. Portfelowa 1, Gdańsk", "ul. Pusta 3, Gdańsk");
    }

    @Test
    void countsOccupiedAvailableAndInventorySeparately() {
        var row = rowFor(letProperty, LocalDate.of(2026, 3, 1));

        assertThat(row.occupancy().occupied()).as("m. 1 is let").isEqualTo(1);
        assertThat(row.occupancy().available()).as("m. 2 is on the market and empty").isEqualTo(1);
        assertThat(row.occupancy().inventory()).as("m. 3 was never advertised").isEqualTo(1);
        assertThat(row.occupancy().total()).isEqualTo(3);
    }

    /**
     * A property a manager has created but not yet filled must appear. Dropping it makes the write
     * look like it failed, which is the read-your-writes trap (najem-build seq 303) met on the
     * first screen of the prototype.
     */
    @Test
    void showsAPropertyWithNoUnitsRatherThanOmittingIt() {
        var row = rowFor(emptyProperty, LocalDate.of(2026, 3, 1));

        assertThat(row.occupancy().total()).isZero();
        assertThat(row.occupancy().available())
            .as("a property with no units has no available units either — not one by default")
            .isZero();
    }

    /**
     * The defect @najem-pm diagnosed in {@code UnitBoardQuery} (seq 357), asserted here because this
     * query shares the predicate: a tenancy that ran and then ended must still count as occupancy
     * for the dates it covered. Excluding every released period erases the history of every
     * completed tenancy.
     */
    @Test
    void stillCountsATenancyThatRanAndThenEnded() {
        assertThat(rowFor(pastProperty, LocalDate.of(2026, 3, 1)).occupancy().occupied())
            .as("occupied on a date inside a tenancy that has since ended")
            .isEqualTo(1);
        assertThat(rowFor(pastProperty, LocalDate.of(2026, 9, 1)).occupancy().occupied())
            .as("and vacant after it ended")
            .isZero();
    }

    @Test
    void tellsAnotherWorkspaceNothingAboutThisOne() {
        assertThat(board.forWorkspace(otherWorkspace, LocalDate.of(2026, 3, 1)))
            .extracting(PropertyBoardQuery.Row::address)
            .containsExactly("ul. Cudza 9, Sopot");
        assertThat(board.forWorkspace(UUID.randomUUID(), LocalDate.of(2026, 3, 1))).isEmpty();
    }

    /**
     * The list and the single-property query must not drift apart — they are two SQL statements
     * answering one question, which is exactly the shape that produced the {@code UnitBoardQuery}
     * disagreement. This is the assertion that would fail if only one of them were corrected.
     */
    @Test
    void agreesWithThePerPropertyQueryForEveryProperty() {
        var asOf = LocalDate.of(2026, 3, 1);

        for (var row : board.forWorkspace(workspace, asOf)) {
            assertThat(row.occupancy())
                .as("list and per-property counts disagree for %s", row.address())
                .isEqualTo(occupancy.countsFor(workspace, row.propertyId(), asOf));
        }
    }

    /**
     * The exact defect: on a date inside a tenancy that has since ended, the unit board must name
     * that tenancy as the occupant. With {@code not p.released} alone it named nobody, because
     * ending a tenancy releases the period just as cancelling one does.
     */
    @Test
    void theUnitBoardStillNamesTheOccupantOfATenancyThatHasSinceEnded() {
        var row = unitRow(pastUnit, LocalDate.of(2026, 3, 1));

        assertThat(row.currentTenancyId())
            .as("a tenancy that ran Jan–Jun was the occupant in March, whatever happened later")
            .isEqualTo(endedTenancy);
    }

    @Test
    void theUnitBoardShowsNoOccupantAfterTheTenancyEnded() {
        assertThat(unitRow(pastUnit, LocalDate.of(2026, 9, 1)).currentTenancyId()).isNull();
    }

    @Test
    void theUnitBoardNamesACurrentOccupant() {
        var row = unitRow(occupiedUnit, LocalDate.of(2026, 3, 1));

        assertThat(row.currentTenancyId()).isEqualTo(currentTenancy);
        assertThat(row.name()).isEqualTo("m. 1");
        assertThat(row.marketState())
            .as("market state is about advertising, never about occupancy (najem-pm, seq 359)")
            .isEqualTo("open");
    }

    /**
     * The two boards must agree on which units are occupied. This is the assertion that fails if
     * only one of them is ever corrected again — the drift @najem-pm found, caught rather than
     * described.
     */
    @Test
    void theTwoBoardsAgreeOnHowManyUnitsAreOccupied() {
        var asOf = LocalDate.of(2026, 3, 1);

        for (var property : board.forWorkspace(workspace, asOf)) {
            long occupiedPerUnit = units.forProperty(workspace, property.propertyId(), asOf).stream()
                .filter(u -> u.currentTenancyId() != null)
                .count();

            assertThat(occupiedPerUnit)
                .as("unit board and property board disagree about %s", property.address())
                .isEqualTo(property.occupancy().occupied());
        }
    }

    @Test
    void theUnitBoardTellsAnotherWorkspaceNothing() {
        assertThat(units.forProperty(otherWorkspace, letProperty, LocalDate.of(2026, 3, 1))).isEmpty();
    }

    private static UnitBoardQuery.Row unitRow(UUID unitId, LocalDate asOf) {
        var propertyId = jdbc.queryForObject(
            "select property_id from reporting_unit_state where unit_id = ?", UUID.class, unitId);
        return units.forProperty(workspace, propertyId, asOf).stream()
            .filter(r -> r.unitId().equals(unitId))
            .findFirst().orElseThrow(() -> new AssertionError("no unit row for " + unitId));
    }

    private static PropertyBoardQuery.Row rowFor(UUID propertyId, LocalDate asOf) {
        return board.forWorkspace(workspace, asOf).stream()
            .filter(r -> r.propertyId().equals(propertyId))
            .findFirst().orElseThrow(() -> new AssertionError("no row for " + propertyId));
    }
}
