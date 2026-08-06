package pl.najem.reporting.application;

import pl.najem.acc.adapter.persistence.PostgresAccounting;
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
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.application.BankLine;
import pl.najem.acc.application.IngestionService;
import pl.najem.acc.application.WarningService;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.application.ChecklistService;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.application.ProcessDueStore;
import pl.najem.pm.application.TenancyService;
import pl.najem.pm.domain.ChangeType;
import pl.najem.pm.domain.ChecklistPhase;
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
 * The flagship. Drives PM and accounting through a real tenancy — reservation, checklist, handover,
 * activation, rent change, charges, a payment — then asserts the timeline tells that story back.
 * <p>
 * Nothing here hand-writes an event: every fact is produced by the owning module's own service, so
 * the projection is tested against what those modules actually emit rather than against Reporting's
 * belief about them.
 */
@Testcontainers
@Tag("integration")
class TenancyTimelineProjectionTest {

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

    static JdbcTemplate jdbc;
    static ProjectionRunner runner;
    static TenancyTimelineProjection projection;

    static UUID workspace;
    static UUID tenancyId;
    static UUID cancelledTenancyId;
    static UUID otherWorkspaceTenancyId;

    @BeforeAll
    static void tellTheWholeStory() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm", "classpath:db/acc", "classpath:db/reporting")
            .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var json = productionMapper();

        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, json, registry);

        var portfolio = new PortfolioService(store, jdbc);
        var tenancies = new TenancyService(store, jdbc, new ProcessDueStore(jdbc));
        var checklists = new ChecklistService(store);
        var invoicing = PostgresAccounting.invoiceService(store, jdbc, PostgresAccounting.warningService(jdbc));
        var ingestion = PostgresAccounting.ingestionService((since, iban) -> List.of(), store, jdbc);
        var reconciliation = PostgresAccounting.reconciliationService(store, jdbc);

        workspace = UUID.randomUUID();
        var propertyId = portfolio.createProperty(workspace, "ul. Kwiatowa 5, Kraków",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        var unitId = portfolio.addUnit(propertyId, "m. 3", new BigDecimal("2400"));
        portfolio.openUnitToRent(unitId, "ready to let");

        cancelledTenancyId = tenancies.reserve(reserve(unitId, LocalDate.of(2026, 3, 1))).tenancyId();
        tenancies.cancelReservation(cancelledTenancyId, "tenant withdrew");

        tenancyId = tenancies.reserve(reserve(unitId, LocalDate.of(2026, 9, 1))).tenancyId();
        checklists.addItem(tenancyId, "keys-handed-over", ChecklistPhase.PRE_ACTIVATION);
        checklists.completeItem(tenancyId, "keys-handed-over");
        tenancies.activate(tenancyId, LocalDate.of(2026, 9, 1));

        // Charging BEFORE the rent change is the natural order, and it is deliberately restored
        // here: it used to throw, because PM and accounting shared one stream per tenancy until
        // EventStore.load began filtering on stream_type. This ordering is the regression test.
        var reference = "NAJEM-TL-1";
        invoicing.postRent(workspace, tenancyId, new BigDecimal("2400"),
            LocalDate.of(2026, 10, 10), reference);
        ingestion.ingest(workspace, new BankLine("tl-ext-1", new BigDecimal("2400"), reference,
            LocalDate.of(2026, 10, 9)));
        reconciliation.confirm(workspace, jdbc.queryForObject(
            "select payment_id from acc_payment where external_id = 'tl-ext-1'", UUID.class));

        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.AGREED_CHANGE);
        tenancies.applyRentChange(tenancyId, LocalDate.of(2027, 1, 1));

        // A second agency's tenancy, so the workspace boundary is asserted rather than assumed.
        var otherWorkspace = UUID.randomUUID();
        var otherProperty = portfolio.createProperty(otherWorkspace, "ul. Inna 1, Gdańsk",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        var otherUnit = portfolio.addUnit(otherProperty, "m. 1", new BigDecimal("1800"));
        otherWorkspaceTenancyId = tenancies.reserve(reserve(otherUnit, LocalDate.of(2026, 9, 1))).tenancyId();

        projection = new TenancyTimelineProjection(jdbc);
        runner = new ProjectionRunner(new EventFeed(jdbc, json), jdbc,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            List.of(projection), 100);
        runner.runOnce();
    }

    private static ReserveTenancy reserve(UUID unitId, LocalDate start) {
        return new ReserveTenancy(null, null, unitId, List.of(UUID.randomUUID()), List.of(),
            start, new Term.FixedTerm(start.plusYears(1)), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2400"), null), 10, null, "NAJEM-" + start);
    }

    private static List<String> kindsFor(UUID subject) {
        return jdbc.queryForList("""
            select kind from reporting_timeline_entry
            where level = 'tenancy' and subject_id = ? order by occurred_on, global_seq
            """, String.class, subject);
    }

    @Test
    void tellsTheTenancysStoryInTheOrderItHappened() {
        assertThat(kindsFor(tenancyId)).containsExactly(
            "tenancy-reserved",             // recorded today, for a September start
            "checklist-item-completed",     // recorded today, no domain date of its own
            "tenancy-activated",            // 2026-09-01
            "payment-allocated",            // 2026-10-09, the day the money arrived
            "charge-posted",                // 2026-10-10, the day it fell due
            "rent-changed");                // 2027-01-01, the day it takes effect
        // Payment before charge is correct, not a bug: the tenant paid a day before it was due.
    }

    /**
     * The one event on the timeline that names no tenancy. It rides the Payment stream and knows
     * only a charge, so placing it needs the derivation chain in V61 — and placing it wrongly would
     * put one agency's payment on another agency's timeline.
     */
    @Test
    void placesAPaymentOnTheRightTenancyThroughTheChargeItSettled() {
        var summaries = jdbc.queryForList("""
            select summary from reporting_timeline_entry
            where level = 'tenancy' and subject_id = ? and kind = 'payment-allocated'
            """, String.class, tenancyId);

        assertThat(summaries).singleElement().asString().contains("2400");

        // And it is dated when the money arrived, not when the allocation was confirmed --
        // PaymentAllocated carries no date at all, so this comes from the PaymentIngested index.
        assertThat(jdbc.queryForObject("""
            select occurred_on from reporting_timeline_entry
            where level = 'tenancy' and subject_id = ? and kind = 'payment-allocated'
            """, LocalDate.class, tenancyId)).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    void scopesEveryEntryToTheWorkspaceThatOwnsTheTenancy() {
        assertThat(jdbc.queryForList("""
            select distinct workspace_id from reporting_timeline_entry
            where level = 'tenancy' and subject_id = ?
            """, UUID.class, tenancyId)).containsExactly(workspace);

        assertThat(jdbc.queryForList("""
            select workspace_id from reporting_timeline_entry where subject_id = ?
            """, UUID.class, otherWorkspaceTenancyId))
            .as("another agency's tenancy must not borrow this workspace")
            .doesNotContain(workspace);
    }

    @Test
    void showsACancelledReservationRatherThanHidingIt() {
        assertThat(kindsFor(cancelledTenancyId))
            .containsExactly("tenancy-reserved", "reservation-cancelled");
    }

    @Test
    void datesEachEntryByWhenTheFactApplies() {
        var activated = jdbc.queryForObject("""
            select occurred_on from reporting_timeline_entry
            where level = 'tenancy' and subject_id = ? and kind = 'tenancy-activated'
            """, LocalDate.class, tenancyId);
        var rentChanged = jdbc.queryForObject("""
            select occurred_on from reporting_timeline_entry
            where level = 'tenancy' and subject_id = ? and kind = 'rent-changed'
            """, LocalDate.class, tenancyId);

        assertThat(activated).isEqualTo(LocalDate.of(2026, 9, 1));
        // Not the day it was decided (2026-10-01) and not the day it was projected: a rent change
        // belongs on the timeline where it takes effect, which is what a manager is looking for.
        assertThat(rentChanged).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void keepsTheWholePayloadSoALaneCanBeAddedWithoutAReplayOfHistory() {
        var detail = jdbc.queryForObject("""
            select detail::text from reporting_timeline_entry
            where level = 'tenancy' and subject_id = ? and kind = 'charge-posted'
            """, String.class, tenancyId);

        assertThat(detail).contains("dueDate").contains("2026-10-10");
    }

    @Test
    void runningTheProjectorAgainChangesNothing() {
        var before = kindsFor(tenancyId);

        runner.runOnce();
        runner.runOnce();

        assertThat(kindsFor(tenancyId)).isEqualTo(before);
    }

    /** Rebuildability is the point of the whole design: discard, replay, get the same story back. */
    @Test
    void rebuildingReproducesTheIdenticalStory() {
        var before = kindsFor(tenancyId);

        runner.rebuild(TenancyTimelineProjection.NAME);

        assertThat(kindsFor(tenancyId)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
            "select count(*) from reporting_tenancy_index", Integer.class)).isPositive();
    }
}
