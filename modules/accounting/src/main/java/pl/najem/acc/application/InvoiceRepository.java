package pl.najem.acc.application;

import pl.najem.acc.domain.Invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The obligations a tenancy carries, and the settling of them.
 *
 * <p>Settlement order is not this interface's business. It returns what is open; the policy of
 * which open invoice money reaches first lives in {@link AllocationService}.
 */
public interface InvoiceRepository {

    /**
     * Every invoice of that tenancy still owing something, in no particular order.
     *
     * <p>A deactivated invoice is not an obligation and never appears here.
     */
    List<Invoice> openInvoices(UUID workspaceId, UUID tenancyId);

    /**
     * Settles part or all of one invoice.
     *
     * <p>The amount is what {@link Invoice#applyPayment} returned, so it is already bounded by what
     * the invoice was owed — this records a settlement that has happened rather than deciding one.
     */
    void applyAllocation(UUID workspaceId, UUID invoiceId, BigDecimal amount);
}
