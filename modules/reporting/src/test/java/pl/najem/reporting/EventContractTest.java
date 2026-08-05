package pl.najem.reporting;

import pl.najem.acc.adapter.persistence.PostgresAccounting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.application.IngestionService;
import pl.najem.acc.application.WarningService;
import pl.najem.acc.application.BankLine;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.application.ChecklistService;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.application.ProcessDueStore;
import pl.najem.pm.application.TenancyService;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.HandoverProtocol;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MeterReading;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Term;
import pl.najem.reporting.application.EventFeed;
import pl.najem.reporting.application.FeedEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tripwire. Decision 1's mitigation, made mandatory per stream by the coordinator's ruling
 * (najem-build seq 65 pt 2).
 * <p>
 * Reporting reads other modules' events as loose JSON, so a module renaming or dropping a field
 * breaks nothing at compile time — it breaks a Timeline, silently, in production. This test drives
 * the <em>owning modules' real services</em> and asserts that every field Reporting depends on is
 * actually present in the stored payload.
 * <p>
 * Driving the real service is the whole point and the reason this test carries test-scope
 * dependencies on two other modules. A hand-written JSON fixture would keep passing forever after
 * PM renamed the field it was imitating — it would assert that Reporting agrees with itself.
 */
@Testcontainers
@Tag("integration")
class EventContractTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    /**
     * Deliberately configured like the APPLICATION's mapper, not like the other modules' test
     * mappers. Boot disables WRITE_DATES_AS_TIMESTAMPS, so production stores dates as ISO strings;
     * a bare {@code new ObjectMapper().registerModule(new JavaTimeModule())} stores them as arrays
     * ([2026,9,1]). Reporting parses payloads, so testing against the wrong encoding would test a
     * shape that never reaches production.
     */
    private static ObjectMapper productionMapper() {
        return JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }

    /**
     * Every field Reporting reads, per stream. Circulated to the owning agents for correction
     * before it was written down (najem-build seq 88).
     * <p>
     * Adding a line here is a promise that Reporting depends on it; removing one releases the
     * owner. Keep it honest in both directions — a tripwire guarding a field nobody reads is noise
     * that trains people to ignore the real ones.
     */
    private record Dependency(String owner, List<String> fields) {
    }

    private static final Map<String, Dependency> REQUIRED = new LinkedHashMap<>();

    private static void requires(String eventType, String owner, String... fields) {
        REQUIRED.put(eventType, new Dependency(owner, List.of(fields)));
    }

    private static final String PM = "najem-pm";
    private static final String ACC = "najem-accounting";

    static {
        // PM — Tenancy stream (@najem-pm)
        requires("TenancyReserved", PM, "workspaceId", "tenancyId", "unitId", "startDate", "legalForm", "monthly");
        requires("TenancyActivated", PM, "workspaceId", "tenancyId", "activatedOn");
        requires("TenancyReservationCancelled", PM, "tenancyId", "reason");
        requires("ChecklistItemCompleted", PM, "tenancyId", "key");
        requires("HandoverProtocolRecorded", PM, "tenancyId", "protocol");
        requires("RentChangeApplied", PM, "tenancyId", "effectiveFrom", "monthly", "type");
        requires("TerminationNoticeGiven", PM, "workspaceId", "tenancyId", "ground", "noticeDate", "effectiveDate");
        requires("TenancyEnded", PM, "workspaceId", "tenancyId", "endDate", "reasonType");
        // PM — Unit stream (@najem-pm)
        requires("TenancyPeriodRegistered", PM, "workspaceId", "unitId", "tenancyId", "start");
        requires("TenancyPeriodReleased", PM, "workspaceId", "unitId", "tenancyId");
        requires("UnitAddedToProperty", PM, "workspaceId", "unitId", "propertyId");
        requires("UnitOpenedToRent", PM, "workspaceId", "unitId");
        requires("UnitClosedToRent", PM, "workspaceId", "unitId");
        // PM — Property stream (@najem-pm)
        requires("PropertyCreated", PM, "workspaceId", "propertyId", "address");
        // Accounting — TenancyLedger stream (@najem-accounting)
        requires("ChargePosted", ACC, "chargeId", "tenancyId", "component", "amount", "dueDate");
        requires("ChargeDeactivated", ACC, "chargeId", "tenancyId");
        requires("CreditNoteIssued", ACC, "creditNoteId", "chargeId", "tenancyId", "amount", "issuedOn");
        // Accounting — Payment stream (@najem-accounting)
        requires("PaymentIngested", ACC, "paymentId", "amount", "bookingDate");
        requires("PaymentAllocated", ACC, "paymentId", "chargeId", "amount");
    }

    /** Who to talk to when a tripwire fires, so the failure is actionable rather than merely loud. */
    private static String ownerOf(String eventType) {
        return REQUIRED.get(eventType).owner();
    }

    static List<FeedEntry> emitted;

    @BeforeAll
    static void driveTheRealServices() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm", "classpath:db/acc", "classpath:db/reporting")
            .load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var json = productionMapper();

        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, json, registry);

        var portfolio = new PortfolioService(store, jdbc);
        var tenancies = new TenancyService(store, jdbc, new ProcessDueStore(jdbc));
        var checklists = new ChecklistService(store);
        var invoicing = PostgresAccounting.invoiceService(store, jdbc, PostgresAccounting.warningService(jdbc));
        var ingestion = new IngestionService((since, iban) -> List.of(), store, jdbc);
        var reconciliation = PostgresAccounting.reconciliationService(store, jdbc);

        var workspace = UUID.randomUUID();
        var propertyId = portfolio.createProperty(workspace, "ul. Testowa 1, Warszawa",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        var unitId = portfolio.addUnit(propertyId, "m. 1", new BigDecimal("2400"));
        portfolio.openUnitToRent(unitId, "ready to let");

        // A cancelled reservation, so TenancyPeriodReleased and the cancellation both really happen.
        var cancelled = tenancies.reserve(reservation(unitId, LocalDate.of(2026, 1, 1)));
        tenancies.cancelReservation(cancelled.tenancyId(), "tenant withdrew");

        var tenancyId = tenancies.reserve(reservation(unitId, LocalDate.of(2026, 9, 1))).tenancyId();
        checklists.addItem(tenancyId, "keys-handed-over", ChecklistPhase.PRE_ACTIVATION);
        checklists.completeItem(tenancyId, "keys-handed-over");
        checklists.recordHandover(tenancyId, new HandoverProtocol(ChecklistPhase.PRE_ACTIVATION,
            List.of(new MeterReading("m-1", "electricity", new BigDecimal("1234"))),
            "clean, no damage", List.of("photo-1"), "doc-1", LocalDate.of(2026, 9, 1)));
        tenancies.activate(tenancyId, LocalDate.of(2026, 9, 1));
        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 9, 2), LocalDate.of(2027, 1, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), pl.najem.pm.domain.ChangeType.AGREED_CHANGE);
        tenancies.applyRentChange(tenancyId, LocalDate.of(2027, 1, 1));

        // Termination and ending, driven BEFORE the accounting block below: PM cannot rehydrate a
        // Tenancy once accounting has written to the shared stream (najem-build seq 103).
        tenancies.giveTerminationNotice(tenancyId, "art. 11 ust. 2 pkt 2",
            LocalDate.of(2027, 2, 1), LocalDate.of(2027, 5, 1), "doc-notice-1");
        tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 3),
            EndReason.LANDLORD_NOTICE, "moved out on time", true));

        portfolio.closeUnitToRent(unitId, "renovation");

        var reference = "NAJEM-TRIPWIRE-1";
        var chargeId = invoicing.postRent(workspace, tenancyId, new BigDecimal("2400"),
            LocalDate.of(2026, 10, 10), reference);
        ingestion.ingest(workspace, new BankLine("ext-1", new BigDecimal("2400"), reference,
            LocalDate.of(2026, 10, 9)));
        reconciliation.confirm(workspace, paymentIdOf(jdbc));
        invoicing.issueCreditNote(workspace, chargeId, new BigDecimal("100"), "goodwill");

        var spare = invoicing.postRent(workspace, tenancyId, new BigDecimal("50"),
            LocalDate.of(2026, 11, 10), "NAJEM-TRIPWIRE-2");
        invoicing.withdraw(workspace, spare, "billed in error");

        emitted = drainFeed(new EventFeed(jdbc, json));
    }

    private static ReserveTenancy reservation(UUID unitId, LocalDate start) {
        return new ReserveTenancy(null, null, unitId, List.of(UUID.randomUUID()), List.of(),
            start, new Term.FixedTerm(start.plusYears(1)), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2400"), null), 10, null, "NAJEM-" + start);
    }

    private static UUID paymentIdOf(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select payment_id from acc_payment where external_id = 'ext-1'", UUID.class);
    }

    private static List<FeedEntry> drainFeed(EventFeed feed) {
        var all = new ArrayList<FeedEntry>();
        for (List<FeedEntry> batch; !(batch = feed.since(all.isEmpty() ? 0
            : all.get(all.size() - 1).globalSeq(), 200)).isEmpty(); ) {
            all.addAll(batch);
        }
        return all;
    }

    @Test
    void everyEventTypeReportingDependsOnIsStillEmittedByItsOwningModule() {
        var missing = REQUIRED.keySet().stream()
            .filter(type -> emitted.stream().noneMatch(e -> e.eventType().equals(type)))
            .map(type -> type + " (owner: " + ownerOf(type) + ")")
            .toList();

        assertThat(missing)
            .as("""
                Reporting depends on these event types, but driving the owning modules' real \
                services produced none of them. Either the event is gone, was renamed, or is no \
                longer emitted on this path — Reporting's timelines depend on it. Talk to the \
                owning agent before changing this.""")
            .isEmpty();
    }

    @Test
    void everyFieldReportingReadsIsStillPresentAndPopulated() {
        var broken = new ArrayList<String>();
        REQUIRED.forEach((eventType, dependency) -> emitted.stream()
            .filter(e -> e.eventType().equals(eventType))
            .findFirst()
            .ifPresent(entry -> dependency.fields().stream()
                .filter(field -> isAbsent(entry.payload(), field))
                .forEach(field -> broken.add(
                    eventType + "." + field + " — Reporting depends on it; talk to @" + ownerOf(eventType)))));

        assertThat(broken)
            .as("Fields Reporting reads that are no longer present or are null in the real payload")
            .isEmpty();
    }

    private static boolean isAbsent(JsonNode payload, String field) {
        var value = payload.get(field);
        return value == null || value.isNull();
    }

    /**
     * The allowlist is a ruling, not a preference: this asserts Reporting never became dependent on
     * a stream it was not granted. A dependency on a private stream would be invisible in the code
     * above — it would just be another entry in REQUIRED.
     */
    @Test
    void everyStreamReportingReadsIsOneItWasGranted() {
        assertThat(emitted).extracting(FeedEntry::streamType)
            .allSatisfy(streamType -> assertThat(EventFeed.ALLOWED_STREAMS).contains(streamType));
    }

    /**
     * Proves the tripwire can actually fail. A test that has never been observed failing is
     * indistinguishable from one that cannot fail — and this one guards against a change nobody
     * intends to make, so it could sit green and useless for a very long time.
     */
    @Test
    void theTripwireFiresWhenAFieldItGuardsIsGone() {
        var activated = emitted.stream()
            .filter(e -> e.eventType().equals("TenancyActivated"))
            .findFirst().orElseThrow();

        assertThat(isAbsent(activated.payload(), "tenancyId")).isFalse();
        assertThat(isAbsent(activated.payload(), "aFieldThatWasRenamedAwayByPm")).isTrue();
    }
}
