package pl.najem.pm.application;

import pl.najem.pm.adapter.persistence.PostgresPortfolioProjection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contracts.events.MoveOutProtocolRecordedEvent;
import pl.najem.contracts.events.RentChangeAppliedEvent;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.contracts.events.TenancyEndedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.domain.ChangeType;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.DocType;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.HandoverProtocol;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MeterReading;
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

/**
 * The producer-side twin of reporting's tripwires: those assert PM still emits what a consumer
 * reads, this asserts PM emits NOTHING ELSE.
 *
 * <p>Both halves matter and they fail differently. A dropped event breaks a consumer loudly. An
 * event added by accident — an internal fact escaping into the outbox — is silent, and the first
 * anyone knows of it is a module consuming PM's private business as though it were a contract.
 * The outbox is a published interface; this test is what makes that a decision rather than a
 * side effect of an append.
 */
@Testcontainers
@Tag("integration")
class OutboundEventsTest {

    /**
     * Everything PM publishes. Adding a line means a contract change and a CCR — it is not a
     * formality, and the coordinator merges `contracts/`, not us.
     */
    private static final List<String> PM_PUBLISHES = List.of(
        "TenancyActivatedEvent",
        "RentChangeAppliedEvent",
        "TenancyEndedEvent",
        "MoveOutProtocolRecordedEvent");

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static PortfolioService portfolio;
    static TenancyService tenancies;
    static ChecklistService checklists;
    static RepairService repairs;
    static ComplianceService compliance;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        registry.register(TenancyActivatedEvent.class);
        registry.register(RentChangeAppliedEvent.class);
        registry.register(TenancyEndedEvent.class);
        registry.register(MoveOutProtocolRecordedEvent.class);
        var store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        portfolio = new PortfolioService(store, new PostgresPortfolioProjection(jdbc));
        tenancies = new TenancyService(store, jdbc, new ProcessDueStore(jdbc));
        checklists = new ChecklistService(store);
        repairs = new RepairService(store, jdbc, portfolio);
        compliance = new ComplianceService(store, jdbc);
    }

    /**
     * Drives the entire module — every command PM has — and asserts the outbox contains only the
     * four published records. Repairs, inspections, documents, comments, corrections, checklists,
     * notices and cancellations must all stay inside PM.
     */
    @Test
    void pmPublishesExactlyTheFourContractRecordsAndNothingElse() {
        var workspaceId = UUID.randomUUID();
        var propertyId = portfolio.createProperty(workspaceId, "Pełna 1", owners());
        var unitId = portfolio.addUnit(workspaceId, propertyId, "M1", new BigDecimal("2500"));

        // Portfolio: none of this is anyone else's business.
        portfolio.openUnitToRent(workspaceId, unitId, "listed");
        portfolio.setUnitBaseRent(workspaceId, unitId, new BigDecimal("2600"));
        portfolio.updateUnitDetails(workspaceId, unitId, java.util.Map.of("listingRef", "OLX-1"));
        compliance.recordInspection(propertyId, pl.najem.pm.domain.InspectionType.GAS,
            LocalDate.of(2026, 5, 10), null, "ok");
        var repairId = repairs.report(RepairScope.UNIT, unitId, "leaking tap", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 8, 1));
        repairs.complete(repairId, LocalDate.of(2026, 8, 3), "done");

        // A reservation that gets cancelled — cancellation is PM's own fact.
        var abandoned = reserve(unitId, LocalDate.of(2029, 1, 1), LocalDate.of(2029, 12, 31));
        tenancies.cancelReservation(abandoned, "tenant withdrew");

        var tenancyId = reserve(unitId, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31));
        checklists.addItem(tenancyId, "keys", ChecklistPhase.PRE_ACTIVATION);
        checklists.completeItem(tenancyId, "keys");
        checklists.recordHandover(tenancyId, new HandoverProtocol(ChecklistPhase.PRE_ACTIVATION,
            List.of(new MeterReading("cw-1", "cold-water", new BigDecimal("100"))),
            "clean", List.of(), null, LocalDate.of(2026, 9, 1)));

        tenancies.activate(tenancyId, LocalDate.of(2026, 9, 1));            // -> Activated
        tenancies.addComment(tenancyId, "parking spot from January");
        tenancies.correctDetails(tenancyId, java.util.Map.of("rentDay", "5"));
        tenancies.attachDocument(tenancyId, DocType.INSURANCE_POLICY, "s3://oc.pdf",
            LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 20));
        tenancies.addTenant(tenancyId, UUID.randomUUID());

        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 10, 1),
            LocalDate.of(2027, 1, 1), new MonthlyAmount(new BigDecimal("2700"), null),
            ChangeType.AGREED_CHANGE);
        tenancies.applyRentChange(tenancyId, LocalDate.of(2027, 1, 1));      // -> RentChange

        tenancies.giveTerminationNotice(tenancyId, "tenant notice", LocalDate.of(2027, 4, 1),
            LocalDate.of(2027, 7, 31), null);
        checklists.recordHandover(tenancyId, new HandoverProtocol(ChecklistPhase.END_OF_TENANCY,
            List.of(new MeterReading("cw-1", "cold-water", new BigDecimal("189.5"))),
            "scuffed", List.of(), "s3://moveout.pdf", LocalDate.of(2027, 8, 2)));  // -> MoveOut
        tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2027, 7, 31),
            LocalDate.of(2027, 8, 2), EndReason.TENANT_NOTICE, "", true));   // -> Ended

        assertThat(publishedFor(tenancyId))
            .containsExactlyInAnyOrder("TenancyActivatedEvent", "RentChangeAppliedEvent",
                "MoveOutProtocolRecordedEvent", "TenancyEndedEvent");
        assertThat(publishedFor(abandoned)).isEmpty();
        assertThat(publishedFor(repairId)).isEmpty();
        assertThat(publishedFor(propertyId)).isEmpty();
    }

    /** Nothing outside the agreed list may reach the outbox, from any command, ever. */
    @Test
    void theoutboxNeverCarriesATypeThatIsNotAPublishedContract() {
        assertThat(jdbc.queryForList("select distinct event_type from outbox", String.class))
            .isSubsetOf(PM_PUBLISHES);
    }

    /**
     * The no-PII rule at the boundary rather than in the record shape. PM carries ContactIds; a
     * tenant's name reaching Accounting through the outbox would be a privacy breach that reads
     * as a convenience.
     */
    @Test
    void nopublishedPayloadCarriesAContactsPersonalData() {
        assertThat(jdbc.queryForList("select payload::text from outbox", String.class))
            .allSatisfy(payload -> assertThat(payload.toLowerCase())
                .doesNotContain("\"email\"").doesNotContain("\"phone\"")
                .doesNotContain("\"surname\"").doesNotContain("\"firstname\""));
    }

    private static List<String> publishedFor(UUID subjectId) {
        return jdbc.queryForList("select event_type from outbox where payload::text like ?",
            String.class, "%" + subjectId + "%");
    }

    private static UUID reserve(UUID unitId, LocalDate start, LocalDate end) {
        return tenancies.reserve(new ReserveTenancy(null, null, unitId,
            List.of(UUID.randomUUID()), List.of(), start, new Term.FixedTerm(end),
            LegalForm.ZWYKLY, new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
    }

    private static List<Owner> owners() {
        return List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")));
    }
}
