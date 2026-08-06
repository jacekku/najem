package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>How long something has waited is arithmetic; what that <em>means</em> is this service's, and
 * the two thresholds live here rather than in a query. An ageing band is a policy about how long a
 * manager may leave money unplaced, and it must not vary with the store.
 */
@Service
@Transactional
public class SuspenseService {

    private final EventStore store;
    private final PaymentRepository payments;
    private final AccountingService accounting;
    private final Clock clock;
    private final int warnAfterDays;
    private final int redAfterDays;

    @Autowired
    public SuspenseService(EventStore store, PaymentRepository payments,
                           AccountingService accounting, Clock clock,
                           @Value("${acc.suspense.warn-after-days:7}") int warnAfterDays,
                           @Value("${acc.suspense.red-after-days:30}") int redAfterDays) {
        this.store = store;
        this.payments = payments;
        this.accounting = accounting;
        this.clock = clock;
        this.warnAfterDays = warnAfterDays;
        this.redAfterDays = redAfterDays;
    }

    /** The domain-model defaults: a decision expected within the week, red at a month. */
    public SuspenseService(EventStore store, PaymentRepository payments,
                           AccountingService accounting) {
        this(store, payments, accounting, Clock.systemDefaultZone(), 7, 30);
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
        return payments.unrested(workspaceId).stream().map(payment -> {
            int daysWaiting = (int) ChronoUnit.DAYS.between(payment.bookingDate(), asOf);
            return new SuspenseEntry(payment.paymentId(), payment.externalId(), payment.amount(),
                payment.title(), payment.counterpartyName(), payment.direction(),
                payment.currency(), payment.bookingDate(), daysWaiting,
                SuspenseAge.of(daysWaiting, warnAfterDays, redAfterDays));
        }).toList();
    }

    /**
     * The manual queue's way out: a manager says which tenancy this money belongs to, and the
     * allocation rules take it from there — oldest due first, rent last, exactly as for a confirmed
     * suggestion. Tier 4 is not a rung the ladder climbs; it is the one a human climbs for it.
     *
     * <p>Without this a line the ladder could not place had no exit at all: {@code confirm} works
     * only from a suggestion, so an overpayment or a garbled reference would have waited forever.
     *
     * <p>A missing payment is not checked for here. It used to be, with its own sentence and its own
     * exception type, which meant one absence had two vocabularies and a caller catching one type
     * caught half the cases. Allocation raises {@link PaymentNotFoundException} for exactly this,
     * and the pre-check existed only because it once failed obscurely instead.
     *
     * @return what came to rest; any remainder stays as the tenant's credit and keeps waiting
     */
    public BigDecimal allocateTo(UUID workspaceId, UUID paymentId, UUID tenancyId) {
        return accounting.allocate(workspaceId, paymentId, tenancyId);
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
        if (!payments.markNonTenant(workspaceId, paymentId, reason, LocalDate.now(clock))) {
            throw new IllegalArgumentException(
                "no payment " + paymentId + " in workspace " + workspaceId);
        }
        var stream = store.load(paymentId, "Payment");
        store.append(paymentId, "Payment", stream.version(),
            List.of(new PaymentMarkedNonTenant(paymentId, reason)), List.of());
    }
}
