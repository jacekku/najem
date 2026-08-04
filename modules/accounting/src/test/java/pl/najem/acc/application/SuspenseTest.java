package pl.najem.acc.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.WorkspaceContext;
import pl.najem.acc.domain.PaymentMarkedNonTenant;
import pl.najem.acc.domain.SuspenseAge;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money that has not come to rest is the reconciliation screen's real subject. It sits in suspense
 * and it ages, because the risk is not that a line is unmatched today — it is that nobody looks at
 * it for a month.
 *
 * <p>Not every line is a tenant's payment. An outgoing utility debit is a real bank fact that will
 * never settle anything, and it must be able to leave the queue by being classified rather than by
 * being matched to something it isn't.
 */
@Testcontainers
class SuspenseTest {

    private static final UUID WS = WorkspaceContext.DEV_WORKSPACE_ID;
    private static final UUID OTHER_WS = UUID.fromString("00000000-0000-0000-0000-0000000000bd");
    private static final LocalDate BOOKED = LocalDate.of(2027, 6, 1);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static LedgerService ledger;
    static IngestionService ingestion;
    static ReconciliationService reconciliation;
    static SuspenseService suspense;
    static JdbcEventStore store;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        ledger = new LedgerService(store, jdbc, new WarningService(jdbc));
        ingestion = new IngestionService((since, iban) -> List.of(), store, jdbc);
        reconciliation = new ReconciliationService(store, jdbc);
        suspense = new SuspenseService(store, jdbc);
    }

    @Test
    void aLineThatMatchesNothingWaitsInSuspense() {
        var paymentId = ingest("tx-s1", "1234.56", "", null);

        assertThat(suspense.waiting(WS, BOOKED)).anySatisfy(entry -> {
            assertThat(entry.paymentId()).isEqualTo(paymentId);
            assertThat(entry.amount()).isEqualByComparingTo("1234.56");
        });
    }

    /**
     * An outgoing debit was recorded and deliberately never matched. Until now it had nowhere to go
     * and would have sat in the queue forever looking like an unresolved tenant payment.
     */
    @Test
    void anOutgoingDebitWaitsToBeClassifiedRatherThanMatched() {
        var paymentId = ingest("tx-s2", "287.43", "OPLATA ZA MEDIA", "DBIT");

        assertThat(idsWaiting(BOOKED)).contains(paymentId);

        suspense.markNonTenant(WS, paymentId, "opłata za media, rachunek wspólnoty");

        assertThat(idsWaiting(BOOKED)).doesNotContain(paymentId);
        assertThat(jdbc.queryForObject("select status from acc_payment where payment_id = ?",
            String.class, paymentId)).isEqualTo("non-tenant");
        assertThat(store.load(paymentId, "Payment").events())
            .anySatisfy(e -> assertThat(e).isInstanceOf(PaymentMarkedNonTenant.class));
    }

    /** Classifying is a judgement about money, so it must say what the judgement was. */
    @Test
    void classifyingWithoutAReasonIsRefused() {
        var paymentId = ingest("tx-s3", "500", "", null);

        assertThatThrownBy(() -> suspense.markNonTenant(WS, paymentId, "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The thresholds are the accountant's to set (hotspot P19); what matters here is that the age
     * bands move at the boundary rather than being decoration.
     */
    @Test
    void suspenseAgesFromFreshThroughWarnToRed() {
        var paymentId = ingest("tx-s4", "900", "", null);

        assertThat(ageOf(paymentId, BOOKED.plusDays(6))).isEqualTo(SuspenseAge.FRESH);
        assertThat(ageOf(paymentId, BOOKED.plusDays(7))).isEqualTo(SuspenseAge.WARN);
        assertThat(ageOf(paymentId, BOOKED.plusDays(29))).isEqualTo(SuspenseAge.WARN);
        assertThat(ageOf(paymentId, BOOKED.plusDays(30))).isEqualTo(SuspenseAge.RED);
    }

    /** Money still owed to nobody is still in suspense, even when part of it has come to rest. */
    @Test
    void anOverpaymentsRemainderKeepsWaiting() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), BOOKED, "NAJEM/S5/2027");
        var paymentId = ingest("tx-s5", "2600", "NAJEM/S5/2027", null);
        suspense.allocateTo(WS, paymentId, tenancyId);

        assertThat(suspense.waiting(WS, BOOKED)).anySatisfy(entry -> {
            assertThat(entry.paymentId()).isEqualTo(paymentId);
            assertThat(entry.amount()).isEqualByComparingTo("600");
        });
    }

    /** Money that has fully come to rest is not waiting for anyone. */
    @Test
    void anAllocatedPaymentLeavesTheQueue() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), BOOKED, "NAJEM/S6/2027");
        var paymentId = ingest("tx-s6", "2000", "NAJEM/S6/2027", null);
        reconciliation.confirm(WS, paymentId);

        assertThat(idsWaiting(BOOKED)).doesNotContain(paymentId);
    }

    /** Another agency's unresolved money is not this agency's problem, or its business. */
    @Test
    void suspenseIsScopedToItsWorkspace() {
        ingestion.ingest(OTHER_WS, new BankLine("tx-s7", new BigDecimal("777"), "", BOOKED));
        var theirs = jdbc.queryForObject("select payment_id from acc_payment where external_id = 'tx-s7'",
            UUID.class);

        assertThat(idsWaiting(BOOKED)).doesNotContain(theirs);
    }

    /** A classification another agency did not make is not theirs to make. */
    @Test
    void anotherWorkspaceCannotClassifyYourMoney() {
        var paymentId = ingest("tx-s8", "640", "", null);

        assertThatThrownBy(() -> suspense.markNonTenant(OTHER_WS, paymentId, "not mine to judge"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(idsWaiting(BOOKED)).contains(paymentId);
    }

    private static UUID ingest(String externalId, String amount, String title, String direction) {
        ingestion.ingest(WS, new BankLine(externalId, new BigDecimal(amount), title, BOOKED,
            null, null, null, BOOKED, direction, "PLN"));
        return jdbc.queryForObject("select payment_id from acc_payment where external_id = ?",
            UUID.class, externalId);
    }

    private static List<UUID> idsWaiting(LocalDate asOf) {
        return suspense.waiting(WS, asOf).stream().map(SuspenseEntry::paymentId).toList();
    }

    private static SuspenseAge ageOf(UUID paymentId, LocalDate asOf) {
        return suspense.waiting(WS, asOf).stream()
            .filter(entry -> entry.paymentId().equals(paymentId))
            .findFirst().orElseThrow().age();
    }
}
