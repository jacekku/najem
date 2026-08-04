package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.PaymentAllocated;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Brings a payment to rest against a tenancy's open charges: oldest due first, and within one due
 * date interest, media, admin, repair recharges, rent last.
 *
 * <p>Money is conserved. What a payment settles plus what it leaves as the tenant's credit is
 * exactly what arrived, and a charge can never be settled beyond its own amount — an overpayment
 * stays credit on the payment rather than being pushed onto an obligation the tenant does not have.
 */
@Service
@Transactional
public class AllocationService {

    /** One open charge, with what is still owed on it. */
    private record OpenCharge(UUID chargeId, Component component, LocalDate dueDate, BigDecimal owed) {}

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public AllocationService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    /**
     * Allocates whatever of the payment is still unallocated across that tenancy's open charges.
     *
     * @return what came to rest; the remainder stays on the payment as the tenant's credit
     */
    public BigDecimal allocate(UUID workspaceId, UUID paymentId, UUID tenancyId) {
        BigDecimal remaining = jdbc.queryForObject("""
            select unallocated_amount from acc_payment where workspace_id = ? and payment_id = ?
            """, BigDecimal.class, workspaceId, paymentId);
        if (remaining == null || remaining.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        var events = new ArrayList<Object>();

        for (OpenCharge charge : openCharges(workspaceId, tenancyId)) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal amount = remaining.min(charge.owed());
            jdbc.update("""
                insert into acc_allocation(allocation_id, workspace_id, payment_id, charge_id,
                                           tenancy_id, component, amount, allocated_on)
                values (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), workspaceId, paymentId, charge.chargeId(), tenancyId,
                charge.component().wireName(), amount, charge.dueDate());
            jdbc.update("""
                update acc_charge
                set allocated_amount = allocated_amount + ?,
                    allocated = allocated_amount + ? >= amount
                where workspace_id = ? and charge_id = ?
                """, amount, amount, workspaceId, charge.chargeId());
            events.add(new PaymentAllocated(paymentId, charge.chargeId(), amount));
            remaining = remaining.subtract(amount);
            allocated = allocated.add(amount);
        }

        if (!events.isEmpty()) {
            var stream = store.load(paymentId, "Payment");
            store.append(paymentId, "Payment", stream.version(), List.copyOf(events), List.of());
        }
        jdbc.update("""
            update acc_payment set unallocated_amount = ?, status = ?
            where workspace_id = ? and payment_id = ?
            """, remaining, statusFor(allocated, remaining), workspaceId, paymentId);
        refreshBoard(workspaceId, tenancyId);
        return allocated;
    }

    /**
     * The board follows the charges, so it is updated by whoever settles them rather than by the
     * path the money took to get here. Confirming a suggestion and allocating by hand are the same
     * event as far as the tenant's standing is concerned.
     *
     * <p>Green means nothing is owed. A tenancy with an open charge left is still awaiting, which is
     * a plainer answer than the unconditional green a confirmed match used to write — the colours
     * proper are task 11's.
     *
     * <p>Package-private so corrections can call it too: reversing allocations reopens charges, and
     * the board must be re-derived rather than assigned. Every writer of charges goes through here.
     */
    void refreshBoard(UUID workspaceId, UUID tenancyId) {
        Integer open = jdbc.queryForObject("""
            select count(*) from acc_charge
            where workspace_id = ? and tenancy_id = ? and active and amount > allocated_amount
            """, Integer.class, workspaceId, tenancyId);
        jdbc.update("""
            insert into acc_tenancy_status(tenancy_id, workspace_id, status) values (?,?,?)
            on conflict (tenancy_id) do update set status = excluded.status
            """, tenancyId, workspaceId, open != null && open > 0 ? "awaiting" : "green");
    }

    /**
     * The tenancy's open charges in settlement order. Deposits are excluded: they are a separate
     * obligation met by a separate transfer, and a deactivated charge is not an obligation at all.
     *
     * <p>Charges sharing a due date and a component are settled in a stable, arbitrary order —
     * they are interchangeable obligations, and determinism matters more than which one goes first.
     */
    private List<OpenCharge> openCharges(UUID workspaceId, UUID tenancyId) {
        return jdbc.query("""
            select charge_id, component, due_date, amount - allocated_amount as owed
            from acc_charge
            where workspace_id = ? and tenancy_id = ? and active and amount > allocated_amount
            """, (rs, i) -> new OpenCharge(rs.getObject(1, UUID.class), Component.of(rs.getString(2)),
                rs.getDate(3).toLocalDate(), rs.getBigDecimal(4)), workspaceId, tenancyId)
            .stream()
            .filter(charge -> charge.component().settledByAutomaticAllocation())
            .sorted(Comparator.comparing(OpenCharge::dueDate)
                .thenComparingInt(charge -> charge.component().allocationRank())
                .thenComparing(OpenCharge::chargeId))
            .toList();
    }

    private static String statusFor(BigDecimal allocated, BigDecimal remaining) {
        if (allocated.signum() == 0) {
            return "unmatched";
        }
        return remaining.signum() == 0 ? "allocated" : "partially-allocated";
    }
}
