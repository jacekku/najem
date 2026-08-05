package pl.najem.acc.application;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import pl.najem.acc.adapter.persistence.PostgresAccounting;
import pl.najem.acc.adapter.persistence.PostgresInvoiceRepository;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.PaymentAllocationAmended;
import pl.najem.acc.domain.PaymentReversed;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two corrections that look alike and are opposites. A reversal says the money was never there —
 * the bank took it back, so every charge it settled is owed again. An amendment says the money is
 * real and went to the wrong tenant, so it moves.
 *
 * <p>Getting them the same way round would be a quiet disaster: reversing as an amendment leaves a
 * tenancy credited with money that does not exist, and amending as a reversal loses a real payment.
 */
@Testcontainers
@Tag("integration")
class ReversalTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final UUID OTHER_WS = UUID.fromString("00000000-0000-0000-0000-0000000000ce");
    private static final LocalDate DUE = LocalDate.of(2027, 7, 10);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static InvoiceService invoicing;
    static IngestionService ingestion;
    static SuspenseService suspense;
    static CorrectionService corrections;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        // The board colours are a function of today, so the clock is fixed a day past DUE. Left on
        // the system clock these assertions would read green/yellow until 2027 and red after it.
        var board = PostgresAccounting.arrearsBoardService(jdbc, Clock.fixed(
            DUE.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
        invoicing = new InvoiceService(store, new PostgresInvoiceRepository(jdbc), PostgresAccounting.warningService(jdbc), board);
        ingestion = new IngestionService((since, iban) -> List.of(), store, jdbc);
        var accounting = new AccountingService(
            PostgresAccounting.allocationService(store, jdbc), board);
        suspense = PostgresAccounting.suspenseService(store, jdbc);
        corrections = new CorrectionService(store, jdbc, accounting, board);
    }

    /** The tenant owes it again, on the day they always owed it. */
    @Test
    void reversingAPaymentReopensTheChargesItSettledAtTheirOriginalDueDates() {
        var tenancyId = UUID.randomUUID();
        var chargeId = invoicing.postRent(WS, tenancyId, new BigDecimal("2000"), DUE, "NAJEM/R1/2027");
        var paymentId = pay("tx-r1", "2000", tenancyId);

        corrections.reverse(WS, paymentId, "brak środków na koncie płatnika");

        assertThat(settled(chargeId)).isEqualByComparingTo("0");
        assertThat(isFullySettled(chargeId)).isFalse();
        assertThat(dueDateOf(chargeId)).isEqualTo(DUE);
        assertThat(store.load(paymentId, "Payment").events())
            .anySatisfy(e -> assertThat(e).isInstanceOf(PaymentReversed.class));
    }

    /**
     * Reversed money is not the tenant's credit — it is nobody's, because it never arrived. Leaving
     * it in suspense would put a manager to work resolving a transfer that does not exist.
     */
    @Test
    void reversedMoneyIsNotCreditAndDoesNotWaitInSuspense() {
        var tenancyId = UUID.randomUUID();
        invoicing.postRent(WS, tenancyId, new BigDecimal("2000"), DUE, "NAJEM/R2/2027");
        var paymentId = pay("tx-r2", "2600", tenancyId);
        assertThat(unallocated(paymentId)).isEqualByComparingTo("600");

        corrections.reverse(WS, paymentId, "chargeback");

        assertThat(unallocated(paymentId)).isEqualByComparingTo("0");
        assertThat(statusOf(paymentId)).isEqualTo("reversed");
        assertThat(suspense.waiting(WS, DUE).stream().map(SuspenseEntry::paymentId))
            .doesNotContain(paymentId);
    }

    /**
     * The board told the manager this tenancy was current. It is not, and it must say so again —
     * in the colour the reopened charge deserves, which past its due date is red rather than a
     * neutral "something is pending".
     */
    @Test
    void reversingTakesTheTenancyBackOffGreen() {
        var tenancyId = UUID.randomUUID();
        invoicing.postRent(WS, tenancyId, new BigDecimal("2000"), DUE, "NAJEM/R3/2027");
        var paymentId = pay("tx-r3", "2000", tenancyId);
        assertThat(boardStatus(tenancyId)).isEqualTo("green");

        corrections.reverse(WS, paymentId, "NSF");

        assertThat(boardStatus(tenancyId)).isEqualTo("red");
    }

    /** Reversing what the bank already took back twice would owe the tenant the money twice. */
    @Test
    void aPaymentCannotBeReversedTwice() {
        var tenancyId = UUID.randomUUID();
        invoicing.postRent(WS, tenancyId, new BigDecimal("2000"), DUE, "NAJEM/R4/2027");
        var paymentId = pay("tx-r4", "2000", tenancyId);
        corrections.reverse(WS, paymentId, "NSF");

        assertThatThrownBy(() -> corrections.reverse(WS, paymentId, "NSF again"))
            .isInstanceOf(IllegalStateException.class);
    }

    /** The money is real. It simply belongs to the other tenant. */
    @Test
    void amendingMovesRealMoneyToTheTenancyItBelongsTo() {
        var wrong = UUID.randomUUID();
        var right = UUID.randomUUID();
        var wrongCharge = invoicing.postRent(WS, wrong, new BigDecimal("2000"), DUE, "NAJEM/R5A/2027");
        var rightCharge = invoicing.postRent(WS, right, new BigDecimal("2000"), DUE, "NAJEM/R5B/2027");
        var paymentId = pay("tx-r5", "2000", wrong);
        assertThat(settled(wrongCharge)).isEqualByComparingTo("2000");

        corrections.amendAllocation(WS, paymentId, right, "wpłata rozpoznana jako czynsz innego najmu");

        assertThat(settled(wrongCharge)).isEqualByComparingTo("0");
        assertThat(settled(rightCharge)).isEqualByComparingTo("2000");
        assertThat(statusOf(paymentId)).isEqualTo("allocated");
        assertThat(store.load(paymentId, "Payment").events())
            .anySatisfy(e -> assertThat(e).isInstanceOf(PaymentAllocationAmended.class));
    }

    /** An amendment moves money; it never creates or destroys any. */
    @Test
    void amendingConservesTheMoney() {
        var wrong = UUID.randomUUID();
        var right = UUID.randomUUID();
        invoicing.postRent(WS, wrong, new BigDecimal("2000"), DUE, "NAJEM/R6A/2027");
        invoicing.postRent(WS, right, new BigDecimal("1500"), DUE, "NAJEM/R6B/2027");
        var paymentId = pay("tx-r6", "2000", wrong);

        corrections.amendAllocation(WS, paymentId, right, "wrong tenancy");

        BigDecimal live = jdbc.queryForObject("""
            select coalesce(sum(amount), 0) from acc_allocation
            where payment_id = ? and not reversed
            """, BigDecimal.class, paymentId);
        assertThat(live.add(unallocated(paymentId))).isEqualByComparingTo("2000");
    }

    /** History is not rewritten: the wrong allocation stays, marked as undone. */
    @Test
    void anUndoneAllocationSurvivesAsTheRecordOfWhatWasDone() {
        var wrong = UUID.randomUUID();
        var right = UUID.randomUUID();
        invoicing.postRent(WS, wrong, new BigDecimal("2000"), DUE, "NAJEM/R7A/2027");
        invoicing.postRent(WS, right, new BigDecimal("2000"), DUE, "NAJEM/R7B/2027");
        var paymentId = pay("tx-r7", "2000", wrong);

        corrections.amendAllocation(WS, paymentId, right, "wrong tenancy");

        assertThat(jdbc.queryForObject("""
            select count(*) from acc_allocation where payment_id = ? and reversed
            """, Integer.class, paymentId)).isEqualTo(1);
    }

    /** A payment nobody in this workspace can see is not theirs to reverse. */
    @Test
    void anotherWorkspaceCannotReverseYourPayment() {
        var tenancyId = UUID.randomUUID();
        var chargeId = invoicing.postRent(WS, tenancyId, new BigDecimal("2000"), DUE, "NAJEM/R8/2027");
        var paymentId = pay("tx-r8", "2000", tenancyId);

        assertThatThrownBy(() -> corrections.reverse(OTHER_WS, paymentId, "not mine"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(settled(chargeId)).isEqualByComparingTo("2000");
    }

    private static UUID pay(String externalId, String amount, UUID tenancyId) {
        ingestion.ingest(WS, new BankLine(externalId, new BigDecimal(amount), "", DUE));
        UUID paymentId = jdbc.queryForObject(
            "select payment_id from acc_payment where external_id = ?", UUID.class, externalId);
        suspense.allocateTo(WS, paymentId, tenancyId);
        return paymentId;
    }

    private static BigDecimal settled(UUID chargeId) {
        return jdbc.queryForObject("select allocated_amount from acc_charge where charge_id = ?",
            BigDecimal.class, chargeId);
    }

    private static boolean isFullySettled(UUID chargeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select allocated from acc_charge where charge_id = ?", Boolean.class, chargeId));
    }

    private static LocalDate dueDateOf(UUID chargeId) {
        return jdbc.queryForObject("select due_date from acc_charge where charge_id = ?",
            java.sql.Date.class, chargeId).toLocalDate();
    }

    private static BigDecimal unallocated(UUID paymentId) {
        return jdbc.queryForObject("select unallocated_amount from acc_payment where payment_id = ?",
            BigDecimal.class, paymentId);
    }

    private static String statusOf(UUID paymentId) {
        return jdbc.queryForObject("select status from acc_payment where payment_id = ?",
            String.class, paymentId);
    }

    private static String boardStatus(UUID tenancyId) {
        return jdbc.queryForObject("select status from acc_tenancy_status where tenancy_id = ?",
            String.class, tenancyId);
    }
}
