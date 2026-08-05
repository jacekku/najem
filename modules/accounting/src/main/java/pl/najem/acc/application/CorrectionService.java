package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.PaymentAllocationAmended;
import pl.najem.acc.domain.PaymentReversed;
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
 * what the ledger did before it was corrected is part of the record.
 */
@Service
@Transactional
public class CorrectionService {

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final AccountingService accounting;
    /**
     * Held directly rather than reached through {@link AccountingService}: unwinding reopens
     * charges without allocating anything, and it refreshes several tenancies at once. It is the
     * same board, needed at a different moment and in a different shape.
     */
    private final ArrearsBoardService board;

    private final Clock clock;

    @Autowired
    public CorrectionService(EventStore store, JdbcTemplate jdbc, AccountingService accounting,
                             ArrearsBoardService board, Clock clock) {
        this.store = store;
        this.jdbc = jdbc;
        this.accounting = accounting;
        this.board = board;
        this.clock = clock;
    }

    /** For tests and callers outside the container, which have no Clock bean to hand. */
    public CorrectionService(EventStore store, JdbcTemplate jdbc, AccountingService accounting,
                             ArrearsBoardService board) {
        this(store, jdbc, accounting, board, Clock.systemDefaultZone());
    }

    /**
     * The bank took the money back. Charges reopen at the due dates they always had — a reversal
     * restores the obligation, it does not reschedule it — and nothing is left as credit, because
     * money that never arrived is nobody's.
     */
    public void reverse(UUID workspaceId, UUID paymentId, String reason) {
        requireReason(reason, "reversing payment " + paymentId);
        String status = statusOf(workspaceId, paymentId);
        if ("reversed".equals(status)) {
            throw new IllegalStateException("payment " + paymentId + " is already reversed");
        }
        unwind(workspaceId, paymentId);
        jdbc.update("""
            update acc_payment
            set status = 'reversed', unallocated_amount = 0, reversal_reason = ?, reversed_on = ?
            where workspace_id = ? and payment_id = ?
            """, reason, LocalDate.now(clock), workspaceId, paymentId);
        append(paymentId, new PaymentReversed(paymentId, reason));
    }

    /**
     * The money went to the wrong tenancy. It is taken back off those charges and put through the
     * ordinary allocation rules against the right one — oldest due first, rent last.
     */
    public BigDecimal amendAllocation(UUID workspaceId, UUID paymentId, UUID tenancyId, String reason) {
        requireReason(reason, "amending payment " + paymentId);
        String status = statusOf(workspaceId, paymentId);
        if ("reversed".equals(status)) {
            throw new IllegalStateException(
                "payment " + paymentId + " was reversed; there is no money to move");
        }
        unwind(workspaceId, paymentId);
        append(paymentId, new PaymentAllocationAmended(paymentId, tenancyId, reason));
        return accounting.allocate(workspaceId, paymentId, tenancyId);
    }

    /**
     * Takes every live allocation of this payment back off its charge and returns the money to the
     * payment. The allocation rows stay, marked reversed.
     *
     * <p>Tenancies that lose a settlement go back to awaiting on the board. Telling a manager a
     * tenancy is current when its payment has been taken back is worse than telling them nothing.
     */
    private void unwind(UUID workspaceId, UUID paymentId) {
        var live = jdbc.queryForList("""
            select charge_id, tenancy_id, amount from acc_allocation
            where workspace_id = ? and payment_id = ? and not reversed
            """, workspaceId, paymentId);
        for (var row : live) {
            // greatest(...) rather than a bare subtraction: if the two ever disagreed the column
            // would go negative, and "amount > allocated_amount" would read the charge as open
            // forever. A floor makes the disagreement loud instead of permanent.
            jdbc.update("""
                update acc_charge
                set allocated_amount = greatest(allocated_amount - ?, 0), allocated = false
                where workspace_id = ? and charge_id = ?
                """, row.get("amount"), workspaceId, row.get("charge_id"));
        }
        // The board is derived, never asserted. Writing 'awaiting' here would be right today and
        // wrong the moment the colours proper exist, and it would look like a colour bug rather
        // than a missing call. Once per tenancy, after the charges have finished moving.
        live.stream().map(row -> (UUID) row.get("tenancy_id")).distinct()
            .forEach(tenancyId -> board.refresh(workspaceId, tenancyId));
        jdbc.update("""
            update acc_payment
            set unallocated_amount = unallocated_amount
                + coalesce((select sum(amount) from acc_allocation
                            where workspace_id = ? and payment_id = ? and not reversed), 0)
            where workspace_id = ? and payment_id = ?
            """, workspaceId, paymentId, workspaceId, paymentId);
        jdbc.update("""
            update acc_allocation set reversed = true
            where workspace_id = ? and payment_id = ? and not reversed
            """, workspaceId, paymentId);
    }

    /** A payment outside the caller's workspace does not exist, rather than being forbidden. */
    private String statusOf(UUID workspaceId, UUID paymentId) {
        var rows = jdbc.queryForList("""
            select status from acc_payment where workspace_id = ? and payment_id = ?
            """, String.class, workspaceId, paymentId);
        if (rows.isEmpty()) {
            // TODO: same absence, two vocabularies. AllocationService now raises
            // PaymentNotFoundException for exactly this sentence. This one also reads the status,
            // so it cannot simply defer to the repository — it wants a payment, not a count, and
            // should move onto PaymentRepository once that port carries status too.
            throw new IllegalArgumentException(
                "no payment " + paymentId + " in workspace " + workspaceId);
        }
        return rows.getFirst();
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
