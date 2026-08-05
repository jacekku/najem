package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One obligation a tenancy carries: what was charged, what is still owed on it, and whether money
 * has reached it.
 *
 * <p>The identifier is the charge row's: the accounting domain calls the obligation an invoice,
 * while property management calls the same row a charge. The name changes at this boundary and the
 * identity does not — and inside this module there is one name for it, not two. A separate
 * "posted charge" type briefly existed for the corrections to look at; it was this, asked a
 * different question.
 *
 * <p>An invoice settles itself rather than being settled from outside, and it may only ever ask a
 * payment for what it is owed. That is what keeps a charge from being settled beyond its own
 * amount, and it holds wherever the entity is used rather than only where someone remembered to
 * take a minimum.
 *
 * <p>{@code paid} is carried rather than derived from {@code owed}, because acc_charge stores it:
 * the statement that moves the money writes the flag in the same breath. Deriving it here would
 * make the entity agree with itself and stop it from ever disagreeing with the database, which is
 * the only disagreement worth catching.
 */
public final class Invoice {

    private final UUID invoiceId;
    private final UUID tenancyId;
    private final Component component;
    private final LocalDate dueDate;
    private final BigDecimal amount;
    private final boolean paid;
    private BigDecimal owed;

    public Invoice(UUID invoiceId, UUID tenancyId, Component component, LocalDate dueDate,
                   BigDecimal amount, BigDecimal owed, boolean paid) {
        this.invoiceId = invoiceId;
        // TODO: move this singular tenancyId into a composite object that gives context to the Invoice (tenancy,property,unit,user and other)
        this.tenancyId = tenancyId;
        this.component = component;
        this.dueDate = dueDate;
        this.amount = amount;
        this.owed = owed;
        this.paid = paid;
    }

    public UUID invoiceId() {
        return invoiceId;
    }

    public UUID tenancyId() {
        return tenancyId;
    }

    public Component component() {
        return component;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    /** What was charged, which a credit note is measured against. */
    public BigDecimal amount() {
        return amount;
    }

    /** What is still owed on this invoice. */
    public BigDecimal owed() {
        return owed;
    }

    public boolean isSettled() {
        return owed.signum() <= 0;
    }

    /**
     * A charge nobody has paid can simply be withdrawn. Once money has reached it the assertion has
     * been acted on and the correction owed to the tenant is a document, not a deletion.
     */
    public boolean canBeWithdrawn() {
        return !paid;
    }

    /** A credit note is only a credit note while it is positive and fits inside what was charged. */
    public boolean acceptsCreditNoteOf(BigDecimal credited) {
        return credited.signum() > 0 && credited.compareTo(amount) <= 0;
    }

    /**
     * Takes from the payment as much as this invoice is owed, or as much as the payment has left,
     * whichever is less. Both sides move together: the payment parts with exactly what this invoice
     * receives.
     *
     * @return what came to rest here, which is what the caller must record
     */
    public BigDecimal applyPayment(Payment payment) {
        BigDecimal amount = payment.take(owed);
        owed = owed.subtract(amount);
        return amount;
    }
}
