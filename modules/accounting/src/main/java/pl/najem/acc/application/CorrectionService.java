package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.PaymentAllocationAmended;
import pl.najem.acc.domain.PaymentReversed;
import pl.najem.acc.domain.PaymentStatus;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The two corrections a payment can need, which look alike and are opposites.
 *
 * <p>A <strong>reversal</strong> says the money was never there: the bank took it back, so every
 * charge it settled is owed again and there is nothing left to allocate. An <strong>amendment</strong>
 * says the money is real and went to the wrong tenant, so it moves.
 *
 * <p>Confusing them would be a quiet disaster in either direction — reversing as an amendment
 * leaves a tenancy credited with money that does not exist, and amending as a reversal loses a real
 * payment. Neither ever deletes an allocation: the undone row survives marked as reversed, because
 * what the books did before they were corrected is part of the record.
 */
@Service
@Transactional
public class CorrectionService {

    private final EventStore store;
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final AccountingRepository allocations;
    private final AccountingService accounting;
    /**
     * Held directly rather than reached through {@link AccountingService}: unwinding reopens
     * charges without allocating anything, and it refreshes several tenancies at once. It is the
     * same board, needed at a different moment and in a different shape.
     */
    private final ArrearsBoardService board;

    private final Clock clock;

    @Autowired
    public CorrectionService(EventStore store, PaymentRepository payments,
                             InvoiceRepository invoices, AccountingRepository allocations,
                             AccountingService accounting, ArrearsBoardService board, Clock clock) {
        this.store = store;
        this.payments = payments;
        this.invoices = invoices;
        this.allocations = allocations;
        this.accounting = accounting;
        this.board = board;
        this.clock = clock;
    }

    /**
     * The bank took the money back. Charges reopen at the due dates they always had — a reversal
     * restores the obligation, it does not reschedule it — and nothing is left as credit, because
     * money that never arrived is nobody's.
     */
    public void reverse(UUID workspaceId, UUID paymentId, String reason) {
        requireReason(reason, "reversing payment " + paymentId);
        if (statusOf(workspaceId, paymentId) == PaymentStatus.REVERSED) {
            throw new IllegalStateException("payment " + paymentId + " is already reversed");
        }
        unwind(workspaceId, paymentId);
        payments.reverse(workspaceId, paymentId, reason, LocalDate.now(clock));
        append(paymentId, new PaymentReversed(paymentId, reason));
    }

    /**
     * The money went to the wrong tenancy. It is taken back off those charges and put through the
     * ordinary allocation rules against the right one — oldest due first, rent last.
     */
    public BigDecimal amendAllocation(UUID workspaceId, UUID paymentId, UUID tenancyId, String reason) {
        requireReason(reason, "amending payment " + paymentId);
        if (statusOf(workspaceId, paymentId) == PaymentStatus.REVERSED) {
            throw new IllegalStateException(
                "payment " + paymentId + " was reversed; there is no money to move");
        }
        unwind(workspaceId, paymentId);
        append(paymentId, new PaymentAllocationAmended(paymentId, tenancyId, reason));
        return accounting.allocate(workspaceId, paymentId, tenancyId);
    }

    /**
     * Takes every live allocation of this payment back off its invoice and returns the money to the
     * payment. The allocation rows stay, marked reversed.
     *
     * <p>Tenancies that lose a settlement go back to awaiting on the board. Telling a manager a
     * tenancy is current when its payment has been taken back is worse than telling them nothing.
     */
    private void unwind(UUID workspaceId, UUID paymentId) {
        var live = allocations.liveAllocations(workspaceId, paymentId);
        for (LiveAllocation allocation : live) {
            invoices.unapplyAllocation(workspaceId, allocation.invoiceId(), allocation.amount());
        }
        // The board is derived, never asserted. Once per tenancy, after the charges have finished
        // moving.
        live.stream().map(LiveAllocation::tenancyId).distinct()
            .forEach(tenancyId -> board.refresh(workspaceId, tenancyId));
        payments.returnUnallocated(workspaceId, paymentId, live.stream()
            .map(LiveAllocation::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        allocations.markReversed(workspaceId, paymentId);
    }

    /**
     * A payment outside the caller's workspace does not exist, rather than being forbidden.
     *
     * <p>Still {@link IllegalArgumentException} rather than {@link PaymentNotFoundException}, which
     * is what allocation raises for the same absence. Unifying them is worth doing and is not a
     * refactoring: the type is visible to callers, and {@code ReversalTest} pins this one. It wants
     * deciding on purpose rather than as a side effect of moving a query.
     */
    private PaymentStatus statusOf(UUID workspaceId, UUID paymentId) {
        return payments.statusOf(workspaceId, paymentId).orElseThrow(
            () -> new IllegalArgumentException(
                "no payment " + paymentId + " in workspace " + workspaceId));
    }

    private void append(UUID paymentId, Object event) {
        var stream = store.load(paymentId, "Payment");
        store.append(paymentId, "Payment", stream.version(), List.of(event), List.of());
    }

    private static void requireReason(String reason, String what) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(what + " needs a reason");
        }
    }
}
