package pl.najem.acc.application;

import pl.najem.acc.domain.CreditNoteIssued;
import pl.najem.acc.domain.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /**
     * What every tenancy in the workspace still owes, in one query.
     *
     * <p>For a register that lists N tenancies at once. {@link #openInvoices} answers for one, so a
     * screen calling it per row turns a page into an N+1 nobody owns — the argument
     * {@code UnitBoardQuery} makes at length, and the reason it resolves occupancy in the query
     * rather than offering a per-unit primitive.
     *
     * <p><b>Same open predicate as {@link #openInvoices}, deliberately and fragilely.</b> Both mean
     * {@code active and amount > allocated_amount}, and a tenancy whose total here disagreed with
     * the sum of the invoices that screen shows would be the worst kind of wrong: quietly, and only
     * for tenancies with a withdrawn invoice. {@code InvoiceRepositoryContractTest} asserts the two
     * agree rather than trusting the two statements to be edited together.
     *
     * <p>A tenancy that owes nothing is ABSENT, not zero. Present-with-zero and absent are the same
     * answer to "what is owed" and only one of them requires the caller to have been told about
     * every tenancy in the workspace — which this port, over the ledger, has no business knowing.
     */
    Map<UUID, BigDecimal> outstandingByTenancy(UUID workspaceId);

    /** Empty when there is no such invoice in that workspace, which is the same thing as none. */
    Optional<Invoice> find(UUID workspaceId, UUID invoiceId);

    /**
     * Settles part or all of one invoice.
     *
     * <p>The amount is what {@link Invoice#applyPayment} returned, so it is already bounded by what
     * the invoice was owed — this records a settlement that has happened rather than deciding one.
     */
    void applyAllocation(UUID workspaceId, UUID invoiceId, BigDecimal amount);

    /**
     * Takes a settlement back off the invoice, reopening what it had covered.
     *
     * <p>Floored at zero rather than trusted to subtract cleanly. If the stored figure and the
     * allocations ever disagreed, a bare subtraction would push it negative and
     * {@code amount > allocated_amount} would then read the invoice as open forever — a floor makes
     * the disagreement loud instead of permanent.
     */
    void unapplyAllocation(UUID workspaceId, UUID invoiceId, BigDecimal amount);

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
