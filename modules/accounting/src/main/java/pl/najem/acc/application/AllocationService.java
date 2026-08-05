package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.Invoice;
import pl.najem.acc.domain.Payment;
import pl.najem.acc.domain.PaymentAllocated;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
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
 *
 * <p>Where the money lands is all this decides. What the tenant's standing looks like afterwards is
 * a consequence of the charges, not part of the settlement rule, so the arrears board is refreshed
 * by {@link AccountingService} rather than from here. Callers who want both want that one.
 */
@Service
@Transactional
public class AllocationService {

    private final EventStore store;
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final AccountingRepository allocations;

    @Autowired
    public AllocationService(EventStore store, PaymentRepository payments,
                             InvoiceRepository invoices, AccountingRepository allocations) {
        this.store = store;
        this.payments = payments;
        this.invoices = invoices;
        this.allocations = allocations;
    }

    /**
     * Allocates whatever of the payment is still unallocated across that tenancy's open charges.
     *
     * @return what came to rest; the remainder stays on the payment as the tenant's credit
     * @throws PaymentNotFoundException if there is no such payment in that workspace
     */
    public BigDecimal allocate(UUID workspaceId, UUID paymentId, UUID tenancyId) {
        Payment payment = payments.getPayment(workspaceId, paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(
                "no payment " + paymentId + " in workspace " + workspaceId));
        if (!payment.hasRemaining()) {
            return BigDecimal.ZERO;
        }
        var events = new ArrayList<PaymentAllocated>();

        for (Invoice invoice : inSettlementOrder(invoices.openInvoices(workspaceId, tenancyId))) {
            if (!payment.hasRemaining()) {
                break;
            }
            // The invoice takes what it is owed from the payment, and both sides move together.
            // What comes back is what has already happened in the domain; the rest of this loop is
            // recording it.
            BigDecimal amount = invoice.applyPayment(payment);
            allocations.recordAllocation(UUID.randomUUID(), workspaceId, paymentId,
                invoice.invoiceId(), tenancyId, invoice.component(), amount, invoice.dueDate());
            invoices.applyAllocation(workspaceId, invoice.invoiceId(), amount);
            events.add(new PaymentAllocated(paymentId, invoice.invoiceId(), amount));
        }

        if (!events.isEmpty()) {
            var stream = store.load(paymentId, "Payment");
            store.append(paymentId, "Payment", stream.version(), List.<Object>copyOf(events), List.of());
        }
        payments.recordSettlement(workspaceId, payment);
        return payment.settled();
    }

    /**
     * The open invoices money may reach, in the order it reaches them. Deposits are excluded: they
     * are a separate obligation met by a separate transfer.
     *
     * <p>Invoices sharing a due date and a component are settled in a stable, arbitrary order —
     * they are interchangeable obligations, and determinism matters more than which one goes first.
     *
     * <p>This is settlement policy, not storage, so it stays here rather than in the repository:
     * the order is the thing this service exists to decide, and it must not vary with the store.
     */
    private static List<Invoice> inSettlementOrder(List<Invoice> open) {
        return open.stream()
            .filter(invoice -> invoice.component().settledByAutomaticAllocation())
            .sorted(Comparator.comparing(Invoice::dueDate)
                .thenComparingInt(invoice -> invoice.component().allocationRank())
                .thenComparing(Invoice::invoiceId))
            .toList();
    }
}
