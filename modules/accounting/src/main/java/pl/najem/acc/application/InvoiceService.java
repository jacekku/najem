package pl.najem.acc.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.ChargeDeactivated;
import pl.najem.acc.domain.ChargePosted;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.CreditNoteIssued;
import pl.najem.acc.domain.Invoice;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The life of an obligation: asserted, withdrawn, or credited.
 *
 * <p>This was {@code LedgerService}, which promised the double-entry ledger of the design research
 * — a chart of accounts, balanced transactions, a checkable trust equation — and delivered the
 * writer of one table. A generic head noun collects: anything touching a charge had a plausible
 * home here.
 *
 * <p>The module now says invoice throughout. It had been calling one table a charge on the way in
 * and an invoice on the way out, with a port for each, which is how a service ends up holding two
 * models of the same row.
 *
 * <p>What stays spelled charge is what is stored: the {@code TenancyLedger} stream type, the
 * {@code ChargePosted} and {@code ChargeDeactivated} events, and the acc_charge table. Those are
 * wire values on data already written; renaming them is a migration, not a rename.
 */
@Service
@Transactional
public class InvoiceService {

    private final EventStore store;
    private final InvoiceRepository invoices;
    private final WarningService warnings;
    private final ArrearsBoardService board;

    public InvoiceService(EventStore store, InvoiceRepository invoices, WarningService warnings,
                          ArrearsBoardService board) {
        this.store = store;
        this.invoices = invoices;
        this.warnings = warnings;
        this.board = board;
    }

    public UUID postRent(UUID workspaceId, UUID tenancyId, BigDecimal amount, LocalDate dueDate,
                         String paymentReference) {
        return postMonth(workspaceId, tenancyId, MonthlyBreakdown.unsplit(amount), dueDate,
            paymentReference).invoiceIds().getFirst();
    }

    /**
     * Charges a single component against a tenancy — a deposit, a repair recharge, interest. The
     * monthly cycle goes through {@link #postMonth}; this is for the obligations that arrive on
     * their own.
     */
    public UUID post(UUID workspaceId, UUID tenancyId, Component component, BigDecimal amount,
                     LocalDate dueDate, String paymentReference) {
        return post(workspaceId, tenancyId, List.of(new InvoiceLine(component, amount)), dueDate,
            paymentReference).getFirst();
    }

    /**
     * Charges one month against a tenancy, one line per contractual component. The tenant is quoted
     * a single total; the record keeps the split the contract carries, or collapses it into rent
     * when the contract carries none.
     */
    public PostedInvoices postMonth(UUID workspaceId, UUID tenancyId, MonthlyBreakdown breakdown,
                                    LocalDate dueDate, String paymentReference) {
        var invoiceIds = post(workspaceId, tenancyId, breakdown.invoiceLines(), dueDate,
            paymentReference);
        var raised = breakdown.warnings();
        warnings.raise(workspaceId, tenancyId, raised);
        return new PostedInvoices(invoiceIds, raised);
    }

    /**
     * Asserting an obligation is three things that go together: the event, the row, and the board
     * that has to change its mind about the tenancy. One line or twelve, the act is the same.
     */
    private List<UUID> post(UUID workspaceId, UUID tenancyId, List<InvoiceLine> lines,
                            LocalDate dueDate, String paymentReference) {
        var toPost = new ArrayList<InvoiceToPost>(lines.size());
        var events = new ArrayList<Object>(lines.size());
        for (InvoiceLine line : lines) {
            UUID invoiceId = UUID.randomUUID();
            toPost.add(new InvoiceToPost(invoiceId, line.component(), line.amount()));
            events.add(new ChargePosted(invoiceId, tenancyId, line.component().wireName(),
                line.amount(), dueDate));
        }

        append(tenancyId, List.copyOf(events));
        invoices.post(workspaceId, tenancyId, List.copyOf(toPost), dueDate, paymentReference);
        board.refresh(workspaceId, tenancyId);
        return toPost.stream().map(InvoiceToPost::invoiceId).toList();
    }

    /**
     * Withdraws an unpaid obligation. The row survives as inactive with a reversal underneath — the
     * record does not delete facts it has asserted.
     */
    public void withdraw(UUID workspaceId, UUID invoiceId, String reason) {
        var invoice = invoiceIn(workspaceId, invoiceId);
        if (!invoice.canBeWithdrawn()) {
            throw new InvoiceAlreadyPaidException(
                "charge " + invoiceId + " is paid and cannot be deactivated; issue a credit note instead");
        }
        append(invoice.tenancyId(),
            List.of(new ChargeDeactivated(invoiceId, invoice.tenancyId(), reason)));
        invoices.withdraw(workspaceId, invoiceId);
        // A withdrawn obligation is not owed, so it must stop colouring the board. Without this a
        // tenancy whose only arrear was billed in error stays red until something else moves.
        board.refresh(workspaceId, invoice.tenancyId());
    }

    /**
     * Corrects an already-paid obligation. It stands and a credit note is issued against it — the
     * tenant is entitled to the document, and the pair is the audit trail.
     */
    public UUID issueCreditNote(UUID workspaceId, UUID invoiceId, BigDecimal amount, String reason) {
        var invoice = invoiceIn(workspaceId, invoiceId);
        if (invoice.canBeWithdrawn()) {
            throw new InvoiceNotPaidException(
                "charge " + invoiceId + " is unpaid; deactivate it instead of issuing a credit note");
        }
        if (!invoice.acceptsCreditNoteOf(amount)) {
            throw new IllegalArgumentException(
                "credit note of " + amount + " does not fit the charge of " + invoice.amount());
        }
        var note = new CreditNoteIssued(UUID.randomUUID(), invoiceId, invoice.tenancyId(), amount,
            reason, invoice.dueDate());
        append(invoice.tenancyId(), List.of(note));
        invoices.recordCreditNote(workspaceId, note);
        return note.creditNoteId();
    }

    /** An invoice outside the caller's workspace does not exist, rather than being forbidden. */
    private Invoice invoiceIn(UUID workspaceId, UUID invoiceId) {
        return invoices.find(workspaceId, invoiceId).orElseThrow(() -> new IllegalArgumentException(
            "no charge " + invoiceId + " in workspace " + workspaceId));
    }

    private void append(UUID tenancyId, List<Object> events) {
        var stream = store.load(tenancyId, "TenancyLedger");
        store.append(tenancyId, "TenancyLedger", stream.version(), events, List.of());
    }
}
