package pl.najem.pm.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.application.AttentionListsQuery.OpenRepairRow;
import pl.najem.pm.application.AttentionListsQuery.TenancyAttentionRow;
import pl.najem.pm.domain.DocType;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.StatutoryDutyHint;
import pl.najem.pm.domain.Term;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AttentionListsQueryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static PortfolioService portfolio;
    static TenancyService tenancies;
    static RepairService repairs;
    static AttentionListsQuery attention;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        registry.register(TenancyActivatedEvent.class);
        var store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        portfolio = new PortfolioService(store, jdbc);
        tenancies = new TenancyService(store, jdbc, new ProcessDueStore(jdbc));
        repairs = new RepairService(store, jdbc, portfolio);
        attention = new AttentionListsQuery(jdbc);
    }

    @Test
    void endingSoonListsTenanciesInsideTheOneMonthWindow() {
        var workspaceId = UUID.randomUUID();
        var tenancyId = activeTenancy(workspaceId, LocalDate.of(2027, 8, 31));

        assertThat(attention.endingSoon(workspaceId, LocalDate.of(2027, 8, 1)))
            .extracting(TenancyAttentionRow::tenancyId).containsExactly(tenancyId);
        assertThat(attention.endingSoon(workspaceId, LocalDate.of(2027, 6, 1))).isEmpty();
    }

    /** An indefinite tenancy has no end, so it can never be "ending soon". */
    @Test
    void anindefiniteTenancyNeverAppearsInEndingSoon() {
        var workspaceId = UUID.randomUUID();
        indefiniteTenancy(workspaceId);

        assertThat(attention.endingSoon(workspaceId, LocalDate.of(2030, 1, 1))).isEmpty();
    }

    /** An already-ended tenancy is not "ending soon" — the list is work still to do. */
    @Test
    void anendedTenancyDropsOffTheList() {
        var workspaceId = UUID.randomUUID();
        var tenancyId = activeTenancy(workspaceId, LocalDate.of(2027, 8, 31));
        jdbc.update("update pm_tenancy set state = 'ENDED' where tenancy_id = ?", tenancyId);

        assertThat(attention.endingSoon(workspaceId, LocalDate.of(2027, 8, 1))).isEmpty();
    }

    @Test
    void startingSoonListsReservedTenanciesInsideTheWindow() {
        var workspaceId = UUID.randomUUID();
        var tenancyId = reservedTenancy(workspaceId, LocalDate.of(2027, 3, 1));

        assertThat(attention.startingSoon(workspaceId, LocalDate.of(2027, 2, 10)))
            .extracting(TenancyAttentionRow::tenancyId).containsExactly(tenancyId);
        assertThat(attention.startingSoon(workspaceId, LocalDate.of(2026, 12, 1))).isEmpty();
    }

    @Test
    void insuranceExpiringUsesTheSameOneMonthWindowAsEndingSoon() {
        var workspaceId = UUID.randomUUID();
        var tenancyId = activeTenancy(workspaceId, LocalDate.of(2028, 8, 31));
        tenancies.attachDocument(tenancyId, DocType.INSURANCE_POLICY, "s3://docs/oc.pdf",
            LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 20));

        assertThat(attention.insuranceExpiring(workspaceId, LocalDate.of(2027, 8, 1)))
            .extracting(TenancyAttentionRow::tenancyId).containsExactly(tenancyId);
        assertThat(attention.insuranceExpiring(workspaceId, LocalDate.of(2027, 6, 1))).isEmpty();
    }

    /** A renewal moves the expiry, so the tenancy leaves the list rather than nagging forever. */
    @Test
    void arenewedPolicyLeavesTheExpiringList() {
        var workspaceId = UUID.randomUUID();
        var tenancyId = activeTenancy(workspaceId, LocalDate.of(2029, 8, 31));
        tenancies.attachDocument(tenancyId, DocType.INSURANCE_POLICY, "s3://docs/oc-1.pdf",
            LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 20));
        tenancies.attachDocument(tenancyId, DocType.INSURANCE_POLICY, "s3://docs/oc-2.pdf",
            LocalDate.of(2027, 9, 1), LocalDate.of(2028, 8, 31), LocalDate.of(2027, 8, 20));

        assertThat(attention.insuranceExpiring(workspaceId, LocalDate.of(2027, 8, 1))).isEmpty();
    }

    @Test
    void openRepairsAreListedAndCompletedOnesAreNot() {
        var workspaceId = UUID.randomUUID();
        var unitId = unitIn(workspaceId);
        var open = repairs.report(RepairScope.UNIT, unitId, "leaking tap", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5));
        var done = repairs.report(RepairScope.UNIT, unitId, "blown bulb", null,
            StatutoryDutyHint.TENANT, LocalDate.of(2026, 9, 5));
        repairs.complete(done, LocalDate.of(2026, 9, 6), "replaced");

        assertThat(attention.openRepairs(workspaceId))
            .extracting(OpenRepairRow::repairId).containsExactly(open);
    }

    /** Every attention query filters on workspace_id — the hard tenancy boundary. */
    @Test
    void noattentionListEverCrossesWorkspaces() {
        var workspaceId = UUID.randomUUID();
        var tenancyId = activeTenancy(workspaceId, LocalDate.of(2027, 8, 31));
        tenancies.attachDocument(tenancyId, DocType.INSURANCE_POLICY, "s3://docs/oc.pdf",
            null, LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 20));
        repairs.report(RepairScope.UNIT, unitIn(workspaceId), "leaking tap", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5));
        var stranger = UUID.randomUUID();

        assertThat(attention.endingSoon(stranger, LocalDate.of(2027, 8, 1))).isEmpty();
        assertThat(attention.startingSoon(stranger, LocalDate.of(2027, 8, 1))).isEmpty();
        assertThat(attention.insuranceExpiring(stranger, LocalDate.of(2027, 8, 1))).isEmpty();
        assertThat(attention.openRepairs(stranger)).isEmpty();
    }

    private static UUID unitIn(UUID workspaceId) {
        var propertyId = portfolio.createProperty(workspaceId, "Testowa 1", owners());
        return portfolio.addUnit(propertyId, "M1", new BigDecimal("2500"));
    }

    private static UUID reservedTenancy(UUID workspaceId, LocalDate startDate) {
        return tenancies.reserve(new ReserveTenancy(null, null, unitIn(workspaceId),
            List.of(UUID.randomUUID()), List.of(), startDate,
            new Term.FixedTerm(startDate.plusYears(1)), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
    }

    private static UUID activeTenancy(UUID workspaceId, LocalDate endDate) {
        var tenancyId = tenancies.reserve(new ReserveTenancy(null, null, unitIn(workspaceId),
            List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 9, 1),
            new Term.FixedTerm(endDate), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
        tenancies.activate(tenancyId, LocalDate.of(2026, 9, 1));
        return tenancyId;
    }

    private static UUID indefiniteTenancy(UUID workspaceId) {
        var tenancyId = tenancies.reserve(new ReserveTenancy(null, null, unitIn(workspaceId),
            List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 9, 1),
            new Term.Indefinite(), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
        tenancies.activate(tenancyId, LocalDate.of(2026, 9, 1));
        return tenancyId;
    }

    private static List<Owner> owners() {
        return List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")));
    }
}
