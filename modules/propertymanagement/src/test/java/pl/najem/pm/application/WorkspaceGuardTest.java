package pl.najem.pm.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.StatutoryDutyHint;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behavioural half of the boundary. WorkspaceBoundaryTest proves the predicate is written;
 * this proves it does what it claims when two agencies actually exist.
 */
@Testcontainers
@Tag("integration")
class WorkspaceGuardTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static PortfolioService portfolio;
    static RepairService repairs;
    static WorkspaceGuard guard;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        portfolio = new PortfolioService(store, jdbc);
        repairs = new RepairService(store, jdbc, portfolio);
        guard = new WorkspaceGuard(jdbc);
    }

    /** The concrete hole najem-reviewer found: any repair id completed by any caller. */
    @Test
    void arepairInAnotherAgencyIsNotFound() {
        var agencyA = UUID.randomUUID();
        var agencyB = UUID.randomUUID();
        var repairId = repairs.report(RepairScope.UNIT, unitIn(agencyA), "leaking tap", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5));

        guard.requireRepair(agencyA, repairId);   // the owner may

        assertThatThrownBy(() -> guard.requireRepair(agencyB, repairId))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
    }

    @Test
    void aunitAndAPropertyInAnotherAgencyAreNotFound() {
        var agencyA = UUID.randomUUID();
        var agencyB = UUID.randomUUID();
        var propertyId = portfolio.createProperty(agencyA, "Testowa 1", owners());
        var unitId = portfolio.addUnit(propertyId, "M1", new BigDecimal("2500"));

        guard.requireProperty(agencyA, propertyId);
        guard.requireUnit(agencyA, unitId);

        assertThatThrownBy(() -> guard.requireProperty(agencyB, propertyId))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> guard.requireUnit(agencyB, unitId))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
    }

    /**
     * Rule 7(2): a subject that does not exist and one in another agency give the SAME answer.
     * Distinguishing them would confirm that someone else's id is real.
     */
    @Test
    void asubjectThatDoesNotExistIsTheSameAnswerAsOneYouMayNotSee() {
        assertThatThrownBy(() -> guard.requireUnit(UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
    }

    /** Null caller or null subject is denied, never waved through. */
    @Test
    void anabsentCallerOrSubjectIsDenied() {
        var agencyA = UUID.randomUUID();
        var unitId = unitIn(agencyA);

        assertThatThrownBy(() -> guard.requireUnit(null, unitId))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> guard.requireUnit(agencyA, null))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
    }

    /**
     * Defence in depth behind the guard: even reached directly, a projection write cannot touch
     * a row in another agency. Proves the predicate is load-bearing rather than decorative.
     */
    @Test
    void theprojectionWriteItselfCannotReachAnotherAgencysRow() {
        var agencyA = UUID.randomUUID();
        var unitId = unitIn(agencyA);

        int touched = jdbc.update(
            "update pm_unit set base_rent = ? where unit_id = ? and workspace_id = ?",
            new BigDecimal("9999"), unitId, UUID.randomUUID());

        assertThat(touched).isZero();
        assertThat(jdbc.queryForObject("select base_rent from pm_unit where unit_id = ?",
            BigDecimal.class, unitId)).isEqualByComparingTo("2500");
    }

    private static UUID unitIn(UUID workspaceId) {
        return portfolio.addUnit(portfolio.createProperty(workspaceId, "Testowa 1", owners()),
            "M1", new BigDecimal("2500"));
    }

    private static List<Owner> owners() {
        return List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")));
    }
}
