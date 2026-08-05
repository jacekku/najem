package pl.najem.acc.application;

import pl.najem.acc.domain.CreditNoteIssued;
import pl.najem.acc.domain.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The obligations a tenancy carries: asserting them, settling them, and correcting them.
 *
 * <p>One port rather than a charge-writing one beside an invoice-reading one. They were the same
 * table under two names, and the seam between them was where the module's vocabulary split.
 *
 * <p>Settlement order is not this interface's business. It returns what is open; the policy of
 * which open invoice money reaches first lives in {@link AllocationService}.
 */
public interface InvoiceRepository {

    /**
     * Writes a month, however many lines the contract splits it into, as one act. They share a due
     * date and a payment reference because they are one month; a caller with two due dates has two
     * postings.
     */
    void post(UUID workspaceId, UUID tenancyId, List<InvoiceToPost> invoices, LocalDate dueDate,
              String paymentReference);

    /**
     * Every invoice of that tenancy still owing something, in no particular order.
     *
     * <p>A withdrawn invoice is not an obligation and never appears here.
     */
    List<Invoice> openInvoices(UUID workspaceId, UUID tenancyId);

    /** Empty when there is no such invoice in that workspace, which is the same thing as none. */
    Optional<Invoice> find(UUID workspaceId, UUID invoiceId);

    /**
     * Settles part or all of one invoice.
     *
     * <p>The amount is what {@link Invoice#applyPayment} returned, so it is already bounded by what
     * the invoice was owed — this records a settlement that has happened rather than deciding one.
     */
    void applyAllocation(UUID workspaceId, UUID invoiceId, BigDecimal amount);

    /** Marks an invoice withdrawn. The row survives — the record does not delete what it asserted. */
    void withdraw(UUID workspaceId, UUID invoiceId);

    /**
     * Files the credit note against the invoice it corrects. The event is the same shape, because
     * the document and the fact of it are the same six things.
     */
    void recordCreditNote(UUID workspaceId, CreditNoteIssued note);

    /**
     * The czynsz in force on a date — the base for deposit valorization, which excludes adminFee and
     * mediaAdvance (DEPOSIT §1). Zero when the tenancy has been charged no rent by then.
     */
    BigDecimal rentInForceOn(UUID workspaceId, UUID tenancyId, LocalDate asOf);
}
