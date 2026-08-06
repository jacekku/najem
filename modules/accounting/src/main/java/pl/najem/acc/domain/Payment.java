package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Money that arrived, and how much of it has not yet come to rest against an obligation.
 *
 * <p>A payment is the only thing that knows what it has left to give, so it is what hands money
 * out: an {@link Invoice} asks, and takes what it is given. Nothing outside can set the remainder,
 * which is what makes the conservation rule hold by construction — every złoty this payment parts
 * with is a złoty some invoice received, because parting with it and receiving it are one call.
 *
 * <p>An overpayment therefore cannot be pushed onto an obligation the tenant does not have. An
 * invoice may only ask for what it is owed, and what nobody asks for stays here as the tenant's
 * credit.
 *
 * <p>It also knows which corrections it is still open to. Whether a payment may be reversed or
 * amended is a fact about the money — reversing twice owes the tenant a sum that never left anyone's
 * account, and amending a reversal credits a tenancy with funds the bank has taken back. A service
 * comparing a status string to a literal could get that right; a payment asked directly gets it
 * right for every caller, including the next one.
 */
public final class Payment {

    private final UUID paymentId;
    /**
     * What the record says has happened to this payment, which is not the same question as
     * {@link #status()}. That one is derived from what has been settled during this allocation;
     * this is what was written down — including the two outcomes settlement cannot produce,
     * {@code reversed} and {@code non-tenant}.
     */
    private final PaymentStatus recorded;
    private BigDecimal remaining;
    private BigDecimal settled = BigDecimal.ZERO;

    /** A payment nobody has judged yet, which is what allocation deals with. */
    public Payment(UUID paymentId, BigDecimal unallocatedAmount) {
        this(paymentId, unallocatedAmount, PaymentStatus.UNMATCHED);
    }

    public Payment(UUID paymentId, BigDecimal unallocatedAmount, PaymentStatus recorded) {
        this.paymentId = paymentId;
        this.remaining = unallocatedAmount;
        this.recorded = recorded;
    }

    public UUID paymentId() {
        return paymentId;
    }

    /** What is still the tenant's credit rather than settled. */
    public BigDecimal remaining() {
        return remaining;
    }

    /** What this payment has come to rest against so far. */
    public BigDecimal settled() {
        return settled;
    }

    public boolean hasRemaining() {
        return remaining.signum() > 0;
    }

    /**
     * Hands over as much of what is asked for as is left, and remembers parting with it.
     *
     * <p>Package-private: an invoice settles itself against a payment, and no one else moves this
     * money. Reaching in from outside the domain would be how the two sides of a settlement come to
     * disagree.
     *
     * @return what was actually given, which is never more than what remains nor more than was asked
     */
    BigDecimal take(BigDecimal wanted) {
        BigDecimal amount = remaining.min(wanted);
        remaining = remaining.subtract(amount);
        settled = settled.add(amount);
        return amount;
    }

    /**
     * Where this payment stands now: untouched, spent to the last grosz, or somewhere between.
     *
     * <p>A payment that settled nothing is unmatched rather than partially allocated — it is waiting
     * for someone to say who it belongs to, and that is a different thing from being spent.
     */
    public PaymentStatus status() {
        if (settled.signum() == 0) {
            return PaymentStatus.UNMATCHED;
        }
        return remaining.signum() == 0 ? PaymentStatus.ALLOCATED : PaymentStatus.PARTIALLY_ALLOCATED;
    }

    /** Whether the bank has already taken this money back. */
    public boolean isReversed() {
        return recorded == PaymentStatus.REVERSED;
    }

    /**
     * The bank cannot take back twice what it has taken back once. Reversing again would reopen the
     * charges this payment settled a second time and owe the tenant a sum that never left anyone's
     * account.
     */
    public void requireReversible() {
        if (isReversed()) {
            throw new PaymentAlreadyReversedException(paymentId);
        }
    }

    /**
     * An amendment moves money that exists. A reversed payment has none to move, and pretending
     * otherwise credits a tenancy with funds the bank has already recovered.
     */
    public void requireAmendable() {
        if (isReversed()) {
            throw new ReversedPaymentHasNothingToMoveException(paymentId);
        }
    }
}
