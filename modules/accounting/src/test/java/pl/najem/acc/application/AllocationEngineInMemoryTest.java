package pl.najem.acc.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.PaymentAllocated;
import pl.najem.acc.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The settlement rules, run against repositories held in memory.
 *
 * <p>This mirrors {@link AllocationEngineTest} scenario for scenario. That one is the authority —
 * it settles real rows through real SQL and is what proves the statements correct. This one exists
 * to answer the same questions in milliseconds while the allocation policy is being changed, so a
 * mistake in the ordering or the arithmetic is caught before a container is ever started.
 *
 * <p>When the two disagree, the Testcontainers test is right and this one is wrong: an in-memory
 * repository is a claim about what the database does, and a claim can be mistaken.
 */
class AllocationEngineInMemoryTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final UUID OTHER_WS = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    private static final LocalDate JANUARY = LocalDate.of(2027, 1, 10);
    private static final LocalDate FEBRUARY = LocalDate.of(2027, 2, 10);

    private InMemoryPaymentRepository payments;
    private InMemoryInvoiceRepository invoices;
    private InMemoryAccountingRepository allocations;
    private RecordingEventStore store;
    private AllocationService allocation;

    @BeforeEach
    void setUp() {
        payments = new InMemoryPaymentRepository();
        invoices = new InMemoryInvoiceRepository();
        allocations = new InMemoryAccountingRepository();
        store = new RecordingEventStore();
        allocation = new AllocationService(store, payments, invoices, allocations);
    }

    @Test
    void oneTransferSettlesSeveralChargesOldestFirst() {
        var tenancyId = UUID.randomUUID();
        var january = rent(tenancyId, "2000", JANUARY);
        var february = rent(tenancyId, "2000", FEBRUARY);
        var paymentId = arrived("4000");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(invoices.settled(january)).isEqualByComparingTo("2000");
        assertThat(invoices.settled(february)).isEqualByComparingTo("2000");
        assertThat(unallocated(paymentId)).isEqualByComparingTo("0");
    }

    /**
     * Rent last is the whole point of the order: rent is what the arrears board and the art. 11
     * full-periods counter watch, so leaving it unpaid is the outcome that stays visible.
     */
    @Test
    void withinOneDueDateRentSettlesLast() {
        var tenancyId = UUID.randomUUID();
        invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("2400"), JANUARY);
        invoices.post(WS, tenancyId, Component.MEDIA_ADVANCE, new BigDecimal("300"), JANUARY);
        invoices.post(WS, tenancyId, Component.ADMIN_FEE, new BigDecimal("300"), JANUARY);
        var paymentId = arrived("600");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(invoices.settledFor(tenancyId, Component.MEDIA_ADVANCE)).isEqualByComparingTo("300");
        assertThat(invoices.settledFor(tenancyId, Component.ADMIN_FEE)).isEqualByComparingTo("300");
        assertThat(invoices.settledFor(tenancyId, Component.RENT)).isEqualByComparingTo("0");
    }

    /** Interest before media, and rent after both — the full order within one due date. */
    @Test
    void withinOneDueDateInterestSettlesFirst() {
        var tenancyId = UUID.randomUUID();
        invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("2000"), JANUARY);
        invoices.post(WS, tenancyId, Component.REPAIR_RECHARGE, new BigDecimal("100"), JANUARY);
        invoices.post(WS, tenancyId, Component.INTEREST, new BigDecimal("50"), JANUARY);
        var paymentId = arrived("50");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(invoices.settledFor(tenancyId, Component.INTEREST)).isEqualByComparingTo("50");
        assertThat(invoices.settledFor(tenancyId, Component.REPAIR_RECHARGE)).isEqualByComparingTo("0");
        assertThat(invoices.settledFor(tenancyId, Component.RENT)).isEqualByComparingTo("0");
    }

    @Test
    void aPartPaymentSettlesWhatItReachesAndLeavesTheRestOpen() {
        var tenancyId = UUID.randomUUID();
        var january = rent(tenancyId, "2000", JANUARY);
        var february = rent(tenancyId, "2000", FEBRUARY);
        var paymentId = arrived("2500");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(invoices.settled(january)).isEqualByComparingTo("2000");
        assertThat(invoices.settled(february)).isEqualByComparingTo("500");
        assertThat(invoices.find(january).fullySettled()).isTrue();
        assertThat(invoices.find(february).fullySettled()).isFalse();
    }

    /** Money the tenant does not owe yet stays theirs: it is credit on the payment, not on a charge. */
    @Test
    void anOverpaymentLeavesCreditRatherThanOversettlingACharge() {
        var tenancyId = UUID.randomUUID();
        var january = rent(tenancyId, "2000", JANUARY);
        var paymentId = arrived("2600");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(invoices.settled(january)).isEqualByComparingTo("2000");
        assertThat(unallocated(paymentId)).isEqualByComparingTo("600");
        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.PARTIALLY_ALLOCATED);
    }

    /** What was settled plus what was left is exactly what arrived. Nothing is created or lost. */
    @Test
    void everyZlotyIsEitherSettledOrLeftAsCredit() {
        var tenancyId = UUID.randomUUID();
        rent(tenancyId, "1500", JANUARY);
        rent(tenancyId, "1500", FEBRUARY);
        var paymentId = arrived("3700");

        BigDecimal allocated = allocation.allocate(WS, paymentId, tenancyId);

        assertThat(allocations.totalAllocatedTo(paymentId)).isEqualByComparingTo(allocated);
        assertThat(allocated.add(unallocated(paymentId))).isEqualByComparingTo("3700");
    }

    /** A withdrawn charge is not an obligation, so money must not come to rest on it. */
    @Test
    void aDeactivatedChargeIsNotSettled() {
        var tenancyId = UUID.randomUUID();
        var withdrawn = rent(tenancyId, "2000", JANUARY);
        var standing = rent(tenancyId, "2000", FEBRUARY);
        invoices.deactivate(withdrawn);
        var paymentId = arrived("2000");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(invoices.settled(withdrawn)).isEqualByComparingTo("0");
        assertThat(invoices.settled(standing)).isEqualByComparingTo("2000");
    }

    /**
     * A deposit is a separate transfer against a separate obligation, so rent money must not drift
     * onto it. Settling a deposit is an explicit act, not a side effect of the monthly cycle.
     */
    @Test
    void rentMoneyDoesNotDriftOntoADepositCharge() {
        var tenancyId = UUID.randomUUID();
        var deposit = invoices.post(WS, tenancyId, Component.DEPOSIT, new BigDecimal("6000"), JANUARY);
        var rent = rent(tenancyId, "2000", FEBRUARY);
        var paymentId = arrived("2000");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(invoices.settled(deposit)).isEqualByComparingTo("0");
        assertThat(invoices.settled(rent)).isEqualByComparingTo("2000");
    }

    /** The boundary is the query: another agency's charges are not visible to settle. */
    @Test
    void allocationNeverReachesAnotherWorkspacesCharges() {
        var tenancyId = UUID.randomUUID();
        var theirs = invoices.post(OTHER_WS, tenancyId, Component.RENT, new BigDecimal("2000"), JANUARY);
        var paymentId = arrived("2000");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(invoices.settled(theirs)).isEqualByComparingTo("0");
        assertThat(unallocated(paymentId)).isEqualByComparingTo("2000");
    }

    /** A payment with nothing left to give is left alone, and nothing is recorded against it. */
    @Test
    void aFullySettledPaymentIsNotAllocatedTwice() {
        var tenancyId = UUID.randomUUID();
        rent(tenancyId, "2000", JANUARY);
        var paymentId = arrived("2000");
        allocation.allocate(WS, paymentId, tenancyId);

        BigDecimal again = allocation.allocate(WS, paymentId, tenancyId);

        assertThat(again).isEqualByComparingTo("0");
        assertThat(allocations.all()).hasSize(1);
    }

    /**
     * A payment that is not there is not the same as a payment with nothing left. Returning zero
     * for both would let a wrong identifier look like an ordinary no-op.
     */
    @Test
    void allocatingAPaymentThatDoesNotExistIsAnError() {
        var tenancyId = UUID.randomUUID();
        rent(tenancyId, "2000", JANUARY);
        var unknown = UUID.randomUUID();

        assertThatThrownBy(() -> allocation.allocate(WS, unknown, tenancyId))
            .isInstanceOf(PaymentNotFoundException.class)
            .hasMessageContaining(unknown.toString());
    }

    /** The workspace is part of the question, so another agency's payment is simply absent. */
    @Test
    void aPaymentInAnotherWorkspaceDoesNotExistHere() {
        var tenancyId = UUID.randomUUID();
        var paymentId = UUID.randomUUID();
        payments.arrived(OTHER_WS, paymentId, new BigDecimal("2000"));

        assertThatThrownBy(() -> allocation.allocate(WS, paymentId, tenancyId))
            .isInstanceOf(PaymentNotFoundException.class);
    }

    /** One event per invoice the payment reached, and none when it reached nothing. */
    @Test
    void oneAllocationEventIsAppendedPerInvoiceSettled() {
        var tenancyId = UUID.randomUUID();
        rent(tenancyId, "2000", JANUARY);
        rent(tenancyId, "2000", FEBRUARY);
        var paymentId = arrived("2500");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(store.appended).hasSize(2);
        assertThat(store.appended).allMatch(event -> event instanceof PaymentAllocated);
    }

    private UUID rent(UUID tenancyId, String amount, LocalDate dueDate) {
        return invoices.post(WS, tenancyId, Component.RENT, new BigDecimal(amount), dueDate);
    }

    private UUID arrived(String amount) {
        UUID paymentId = UUID.randomUUID();
        payments.arrived(WS, paymentId, new BigDecimal(amount));
        return paymentId;
    }

    private BigDecimal unallocated(UUID paymentId) {
        return payments.find(WS, paymentId).unallocatedAmount();
    }

    private PaymentStatus statusOf(UUID paymentId) {
        return payments.find(WS, paymentId).status();
    }
}
