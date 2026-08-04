package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.PaymentMarkedNonTenant;
import pl.najem.acc.domain.SuspenseAge;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The queue of money that has not come to rest, and the one way out of it that is not a match.
 *
 * <p>Suspense is derived rather than stored: a payment is waiting when it still holds unallocated
 * money and nobody has judged it to be something other than a tenant's payment. A stored queue
 * would be a second source of truth that can disagree with the payments themselves.
 */
@Service
@Transactional
public class SuspenseService {

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final AllocationService allocation;
    private final Clock clock;
    private final int warnAfterDays;
    private final int redAfterDays;

    @Autowired
    public SuspenseService(EventStore store, JdbcTemplate jdbc, AllocationService allocation,
                           Clock clock,
                           @Value("${acc.suspense.warn-after-days:7}") int warnAfterDays,
                           @Value("${acc.suspense.red-after-days:30}") int redAfterDays) {
        this.store = store;
        this.jdbc = jdbc;
        this.allocation = allocation;
        this.clock = clock;
        this.warnAfterDays = warnAfterDays;
        this.redAfterDays = redAfterDays;
    }

    /** The domain-model defaults: a decision expected within the week, red at a month. */
    public SuspenseService(EventStore store, JdbcTemplate jdbc) {
        this(store, jdbc, new AllocationService(store, jdbc), Clock.systemDefaultZone(), 7, 30);
    }

    /**
     * Today, as this module reckons it. Callers that have no date of their own use this rather than
     * {@code LocalDate.now()} — an ageing band is a boundary, and a boundary tested against the
     * wall clock is a test that passes or fails depending on the day it runs.
     */
    public List<SuspenseEntry> waiting(UUID workspaceId) {
        return waiting(workspaceId, LocalDate.now(clock));
    }

    /**
     * Everything still waiting in this workspace, oldest first, with how long it has waited.
     *
     * @param asOf the date the ages are measured against — passed in rather than read from a clock,
     *             so an ageing rule can be tested at its boundary instead of near it
     */
    public List<SuspenseEntry> waiting(UUID workspaceId, LocalDate asOf) {
        return jdbc.query("""
            select payment_id, external_id, unallocated_amount, title, counterparty_name,
                   direction, currency, booking_date
            from acc_payment
            where workspace_id = ? and unallocated_amount > 0 and status <> 'non-tenant'
            order by booking_date, external_id
            """, (rs, i) -> {
                LocalDate bookingDate = rs.getDate(8).toLocalDate();
                int daysWaiting = (int) ChronoUnit.DAYS.between(bookingDate, asOf);
                return new SuspenseEntry(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getBigDecimal(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), bookingDate, daysWaiting,
                    SuspenseAge.of(daysWaiting, warnAfterDays, redAfterDays));
            }, workspaceId);
    }

    /**
     * The manual queue's way out: a manager says which tenancy this money belongs to, and the
     * allocation rules take it from there — oldest due first, rent last, exactly as for a confirmed
     * suggestion. Tier 4 is not a rung the ladder climbs; it is the one a human climbs for it.
     *
     * <p>Without this a line the ladder could not place had no exit at all: {@code confirm} works
     * only from a suggestion, so an overpayment or a garbled reference would have waited forever.
     *
     * @return what came to rest; any remainder stays as the tenant's credit and keeps waiting
     */
    public BigDecimal allocateTo(UUID workspaceId, UUID paymentId, UUID tenancyId) {
        Integer mine = jdbc.queryForObject("""
            select count(*) from acc_payment where workspace_id = ? and payment_id = ?
            """, Integer.class, workspaceId, paymentId);
        if (mine == null || mine == 0) {
            throw new IllegalArgumentException(
                "no payment " + paymentId + " in workspace " + workspaceId);
        }
        return allocation.allocate(workspaceId, paymentId, tenancyId);
    }

    /**
     * Records that this money was never a tenant's — an outgoing utility debit, a bank fee, an
     * owner's own transfer. The line stays on the books as the bank fact it is and leaves the queue.
     *
     * <p>The reason is required. Classifying is a judgement about money, and a judgement that
     * cannot say what it was is one nobody can review later.
     */
    public void markNonTenant(UUID workspaceId, UUID paymentId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                "classifying payment " + paymentId + " as non-tenant needs a reason");
        }
        int classified = jdbc.update("""
            update acc_payment set status = 'non-tenant', non_tenant_reason = ?, classified_on = ?
            where workspace_id = ? and payment_id = ?
            """, reason, LocalDate.now(clock), workspaceId, paymentId);
        if (classified == 0) {
            throw new IllegalArgumentException(
                "no payment " + paymentId + " in workspace " + workspaceId);
        }
        var stream = store.load(paymentId, "Payment");
        store.append(paymentId, "Payment", stream.version(),
            List.of(new PaymentMarkedNonTenant(paymentId, reason)), List.of());
    }
}
