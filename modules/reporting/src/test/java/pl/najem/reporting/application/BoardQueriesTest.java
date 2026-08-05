package pl.najem.reporting.application;

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
 * <p>
 * {@link SearchQuery} is tested here too, at the bottom: it reads the same two tables, and the one
 * fixture already holds two workspaces each owning a property and a unit — with a unit name
 * deliberately shared between them, which is what makes the scoping assertions non-vacuous.
 */
@Testcontainers
@Tag("integration")
class BoardQueriesTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static PropertyBoardQuery board;
    static PropertyOccupancy occupancy;
    static UnitBoardQuery units;
    static SearchQuery search;

    static UUID workspace;
    static UUID otherWorkspace;
    static UUID letProperty;
    static UUID emptyProperty;
    static UUID pastProperty;
    static UUID pastUnit;
    static UUID endedTenancy;
    static UUID currentTenancy;
    static UUID occupiedUnit;
    static UUID otherProperty;
    static UUID otherUnit;

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

        // Another agency's portfolio entirely. Its unit is named "m. 2" ON PURPOSE — the same name
        // as one of this workspace's units, so a search for that name has something to leak.
        otherProperty = portfolio.createProperty(otherWorkspace, "ul. Cudza 9, Sopot",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        otherUnit = portfolio.addUnit(otherProperty, "m. 2", new BigDecimal("2000"));
        portfolio.openUnitToRent(otherUnit, "ready");

        board = new PropertyBoardQuery(jdbc);
        occupancy = new PropertyOccupancy(jdbc);
        units = new UnitBoardQuery(jdbc);
        search = new SearchQuery(jdbc);
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
        var rows = board.forWorkspace(workspace, asOf);

        // @najem-reviewer, seq 387: a for-loop over a query result passes trivially if the query
        // returns nothing, and this test is a DRIFT GUARD meant to survive edits nobody has made
        // yet. Without this line, a future change that empties the board turns the guard green.
        assertThat(rows).hasSize(3);
        for (var row : rows) {
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
        var properties = board.forWorkspace(workspace, asOf);

        assertThat(properties).hasSize(3);   // as above: the loop below is now known to have run
        for (var property : properties) {
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

    // ------------------------------------------------------------------------------------------
    // search. Here rather than in a class of its own because a second @Testcontainers class is a
    // second postgres, and this fixture already holds the two things search must not confuse: two
    // workspaces, each owning a property and a unit, with one unit name deliberately shared.
    // ------------------------------------------------------------------------------------------

    @Test
    void findsAPropertyByAFragmentOfItsAddress() {
        assertThat(search.search(workspace, "Portfelowa"))
            .containsExactly(new SearchQuery.Hit("property", letProperty, "ul. Portfelowa 1, Gdańsk", letProperty));
    }

    @Test
    void findsAUnitAndLabelsItWithItsProperty() {
        assertThat(search.search(workspace, "m. 3"))
            .singleElement()
            .satisfies(hit -> {
                assertThat(hit.kind()).isEqualTo("unit");
                assertThat(hit.propertyId())
                    .as("a hit is only useful if the caller can navigate to it")
                    .isEqualTo(letProperty);
                assertThat(hit.label())
                    .as("\"m. 3\" alone names nothing on a portfolio-wide search")
                    .isEqualTo("ul. Portfelowa 1, Gdańsk — m. 3");
            });
    }

    @Test
    void matchesRegardlessOfCase() {
        assertThat(search.search(workspace, "pORTFELOWA")).hasSize(1);
    }

    /**
     * The first of @najem-reviewer's three scoping assertions (najem-build seq 350): a PROPERTY
     * belonging to another workspace must be absent. Asserted as a gated population rather than as
     * "the foreign row is missing" — {@code containsExactlyInAnyOrder} fails both if B's property
     * leaks in and if any of A's stop being found, so it cannot pass vacuously.
     */
    @Test
    void aPropertyInAnotherWorkspaceIsNotFound() {
        assertThat(search.search(workspace, "ul."))
            .extracting(SearchQuery.Hit::label)
            .as("every address in the fixture contains \"ul.\", including the other agency's")
            .contains("ul. Portfelowa 1, Gdańsk", "ul. Historyczna 2, Gdańsk", "ul. Pusta 3, Gdańsk")
            .doesNotContain("ul. Cudza 9, Sopot");

        assertThat(search.search(otherWorkspace, "ul."))
            .extracting(SearchQuery.Hit::label)
            .as("and the other agency sees its own property and nothing of ours")
            .contains("ul. Cudza 9, Sopot")
            .doesNotContain("ul. Portfelowa 1, Gdańsk", "ul. Historyczna 2, Gdańsk", "ul. Pusta 3, Gdańsk");
    }

    /**
     * The second: a UNIT belonging to another workspace, with <b>the same name</b> as one of ours.
     * A shared name is what makes this non-vacuous — if the unit branch dropped its predicate, the
     * property branch would still be scoped correctly and every other test here would pass.
     */
    @Test
    void aUnitInAnotherWorkspaceIsNotFound() {
        assertThat(search.search(workspace, "m. 2"))
            .extracting(SearchQuery.Hit::id)
            .as("both workspaces have a unit named \"m. 2\"; only ours may come back")
            .containsExactly(unitNamed("m. 2"))
            .doesNotContain(otherUnit);

        assertThat(search.search(otherWorkspace, "m. 2"))
            .extracting(SearchQuery.Hit::id)
            .containsExactly(otherUnit);
    }

    /**
     * The label is built by a join, so it is a second place a foreign address could appear.
     * <p>
     * <b>This does not guard the join's workspace predicate, and I checked rather than assuming.</b>
     * Removing {@code pr.workspace_id = u.workspace_id} leaves every test here green — {@code
     * property_id} is a globally unique primary key, so the join can only reach the right row. What
     * this test does cover is the unit branch's own predicate: it fails alongside
     * {@link #aUnitInAnotherWorkspaceIsNotFound()} when that one is removed. Recorded this way
     * because the name reads like a guard on the join, and it is not one.
     */
    @Test
    void aUnitIsNeverLabelledWithAnotherWorkspacesAddress() {
        assertThat(search.search(workspace, "m."))
            .extracting(SearchQuery.Hit::label)
            .isNotEmpty()
            .allSatisfy(label -> assertThat(label).doesNotContain("Cudza"));
    }

    @Test
    void aBlankTermIsNotAnInvitationToListEverything() {
        assertThat(search.search(workspace, "")).isEmpty();
        assertThat(search.search(workspace, "   ")).isEmpty();
        assertThat(search.search(workspace, null)).isEmpty();
    }

    /** A wildcard typed into a search box is a character, not a query for the whole portfolio. */
    @Test
    void aPercentSignIsATermRatherThanAWildcard() {
        assertThat(search.search(workspace, "%")).isEmpty();
        assertThat(search.search(workspace, "_")).isEmpty();
    }

    private static UUID unitNamed(String name) {
        return jdbc.queryForObject(
            "select unit_id from reporting_unit_state where workspace_id = ? and name = ?",
            UUID.class, workspace, name);
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
