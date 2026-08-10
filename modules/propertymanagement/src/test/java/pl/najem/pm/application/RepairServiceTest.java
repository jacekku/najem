package pl.najem.pm.application;

import pl.najem.pm.adapter.persistence.PostgresPropertyManagement;

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
import pl.najem.pm.domain.Repair;
import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.StatutoryDutyHint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class RepairServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static PortfolioService portfolio;
    static RepairService repairs;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        portfolio = PostgresPropertyManagement.portfolioService(store, jdbc);
        repairs = PostgresPropertyManagement.repairService(store, jdbc);
    }

    /** Same rule as units: a child never takes a caller-supplied workspace. */
    @Test
    void arepairOnAUnitInheritsTheWorkspaceOfThatUnit() {
        var workspaceId = UUID.randomUUID();
        var unitId = unitIn(workspaceId);

        var repairId = repairs.report(workspaceId, RepairScope.UNIT, unitId, "leaking tap", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5));

        assertThat(Repair.from(store.load(repairId, "Repair").events()).workspaceId())
            .isEqualTo(workspaceId);
        assertThat(jdbc.queryForObject("select workspace_id from pm_repair where repair_id = ?",
            UUID.class, repairId)).isEqualTo(workspaceId);
    }

    @Test
    void arepairOnAPropertyInheritsTheWorkspaceOfThatProperty() {
        var workspaceId = UUID.randomUUID();
        var propertyId = portfolio.createProperty(workspaceId, "Testowa 1", owners()).propertyId();

        var repairId = repairs.report(workspaceId, RepairScope.PROPERTY, propertyId, "roof leak", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5));

        assertThat(Repair.from(store.load(repairId, "Repair").events()).workspaceId())
            .isEqualTo(workspaceId);
    }

    @Test
    void completingClosesTheRepairInBothTheStreamAndTheProjection() {
        var workspaceId = UUID.randomUUID();
        var repairId = repairs.report(workspaceId, RepairScope.UNIT, unitIn(workspaceId),
            "leaking tap", null, StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5));

        repairs.complete(workspaceId, repairId, LocalDate.of(2026, 9, 10), "plumber done");

        assertThat(Repair.from(store.load(repairId, "Repair").events()).isOpen()).isFalse();
        assertThat(jdbc.queryForObject("select completed_on from pm_repair where repair_id = ?",
            LocalDate.class, repairId)).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    /**
     * The repair outlives the tenancy that caused it. Nothing about ending a tenancy touches a
     * repair, which is the point of hanging it off the asset — this pins that the link is a
     * recorded cause and not an ownership that could be cleaned up with the tenancy.
     */
    @Test
    void arepairSurvivesTheTenancyThatCausedIt() {
        var workspaceId = UUID.randomUUID();
        var unitId = unitIn(workspaceId);
        var tenancyId = UUID.randomUUID();
        var repairId = repairs.report(workspaceId, RepairScope.UNIT, unitId, "cracked basin", tenancyId,
            StatutoryDutyHint.NEGOTIABLE, LocalDate.of(2026, 9, 5));

        var repair = Repair.from(store.load(repairId, "Repair").events());

        assertThat(repair.assetId()).isEqualTo(unitId);
        assertThat(repair.causedByTenancy()).isEqualTo(tenancyId);
        assertThat(repair.statutoryDutyHint()).isEqualTo(StatutoryDutyHint.NEGOTIABLE);
    }

    private static UUID unitIn(UUID workspaceId) {
        var propertyId = portfolio.createProperty(workspaceId, "Testowa 1", owners()).propertyId();
        return portfolio.addUnit(workspaceId, propertyId, "M1", new BigDecimal("2500"));
    }

    private static List<Owner> owners() {
        return List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")));
    }
}
