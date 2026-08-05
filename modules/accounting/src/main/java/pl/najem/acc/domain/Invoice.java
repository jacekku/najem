package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One obligation a tenancy carries, and what is still owed on it.
 *
 * <p>The identifier is the charge row's: the accounting domain calls the obligation an invoice,
 * while property management calls the same row a charge. The name changes at this boundary and the
 * identity does not.
 *
 * <p>An invoice settles itself rather than being settled from outside, and it may only ever ask a
 * payment for what it is owed. That is what keeps a charge from being settled beyond its own
 * amount, and it holds wherever the entity is used rather than only where someone remembered to
 * take a minimum.
 */
public final class Invoice {

    private final UUID invoiceId;
    private final Component component;
    private final LocalDate dueDate;
    private BigDecimal owed;

    public Invoice(UUID invoiceId, Component component, LocalDate dueDate, BigDecimal owed) {
        this.invoiceId = invoiceId;
        this.component = component;
        this.dueDate = dueDate;
        this.owed = owed;
    }

    public UUID invoiceId() {
        return invoiceId;
    }

    public Component component() {
        return component;
    }

    public LocalDate dueDate() {
        return dueDate;
    }

    /** What is still owed on this invoice. */
    public BigDecimal owed() {
        return owed;
    }

    public boolean isSettled() {
        return owed.signum() <= 0;
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
