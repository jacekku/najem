package pl.najem.acc.application;

import pl.najem.acc.domain.Payment;
import pl.najem.acc.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payments as the accounting domain needs them: what arrived and how much of it has not yet come
 * to rest against an obligation.
 */
public interface PaymentRepository {

    /**
     * The payment, holding what is still the tenant's credit rather than settled.
     *
     * <p>Empty when no such payment exists in that workspace. Absence is the repository's answer,
     * not its problem: whether a missing payment is an error depends on what the caller was doing,
     * so the caller decides.
     */
    Optional<Payment> getPayment(UUID workspaceId, UUID paymentId);

    /** Records where the payment came to rest once allocation has run. */
    void recordSettlement(UUID workspaceId, Payment payment);

    /**
     * Every payment in the workspace still holding money nobody has placed, oldest booking first.
     *
     * <p>Derived, not stored. A payment is waiting when it still has unallocated money and nobody
     * has judged it to be something other than a tenant's payment — a stored queue would be a
     * second source of truth able to disagree with the payments themselves.
     */
    List<UnrestedPayment> unrested(UUID workspaceId);

    /**
     * Marks the payment as never having been a tenant's, and says whether there was one to mark.
     *
     * <p>False rather than an exception: whether a missing payment is an error depends on what the
     * caller was doing, and this port does not know.
     */
    boolean markNonTenant(UUID workspaceId, UUID paymentId, String reason, LocalDate classifiedOn);

    /**
     * The account the money came from, when the bank told us. Empty for a statement format that
     * carries no counterparty — which is why nothing may be learned from its absence.
     */
    Optional<String> payerAccountOf(UUID workspaceId, UUID paymentId);

    /** Where the payment stands. Empty when there is no such payment in that workspace. */
    Optional<PaymentStatus> statusOf(UUID workspaceId, UUID paymentId);

    /**
     * Records that the money was never there: the bank took it back. Nothing is left as credit,
     * because money that never arrived is nobody's.
     */
    void reverse(UUID workspaceId, UUID paymentId, String reason, LocalDate reversedOn);

    /** Returns money to the payment when an allocation is undone, leaving its status alone. */
    void returnUnallocated(UUID workspaceId, UUID paymentId, BigDecimal amount);

    /**
     * Whether this bank line has already been taken in. The bank's own identifier is the key, which
     * is what makes re-reading a statement harmless: an at-least-once feed is ordinary, and the same
     * transfer arriving twice must not become two payments.
     */
    boolean alreadyIngested(UUID workspaceId, String externalId);

    /**
     * Takes the line in, holding all of it as unplaced money. The identifier is the caller's,
     * because the event carries it too and the two must name the same payment.
     */
    void record(UUID workspaceId, UUID paymentId, BankLine line);

    /** Says a rung of the ladder answered, so the line is no longer merely unmatched. */
    void markSuggested(UUID workspaceId, UUID paymentId);
}
