package pl.najem.acc.application;

import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An {@link InvoiceRepository} kept in a map.
 *
 * <p>Insertion order is preserved so that a test which posts two interchangeable invoices can still
 * reason about which one money reaches first — the ordering itself is imposed by
 * {@link AllocationService}, not here.
 */
public class InMemoryInvoiceRepository implements InvoiceRepository {

    /**
     * An invoice as this repository stores it — the columns, not the entity.
     *
     * <p>{@code allocated} is a stored field rather than a comparison of the other two, because
     * that is what acc_charge holds: the SQL writes the flag in the same statement that moves the
     * amount. Deriving it here would make {@link #fullySettled()} true by construction, and the
     * assertion it supports would be incapable of failing however wrong the real expression got.
     */
    public record Stored(UUID workspaceId, UUID tenancyId, Component component, BigDecimal amount,
                         BigDecimal allocatedAmount, LocalDate dueDate, boolean active,
                         boolean allocated) {

        public BigDecimal owed() {
            return amount.subtract(allocatedAmount);
        }

        public boolean fullySettled() {
            return allocated;
        }
    }

    private final Map<UUID, Stored> invoices = new LinkedHashMap<>();

    /** Posts an obligation and returns its identifier, as the ledger would. */
    public UUID post(UUID workspaceId, UUID tenancyId, Component component, BigDecimal amount,
                     LocalDate dueDate) {
        UUID invoiceId = UUID.randomUUID();
        invoices.put(invoiceId, new Stored(workspaceId, tenancyId, component, amount,
            BigDecimal.ZERO, dueDate, true, false));
        return invoiceId;
    }

    /** Withdraws an obligation billed in error. It stays on file and stops being owed. */
    public void deactivate(UUID invoiceId) {
        Stored stored = invoices.get(invoiceId);
        if (stored == null) {
            throw new IllegalArgumentException("no invoice " + invoiceId);
        }
        invoices.put(invoiceId, new Stored(stored.workspaceId(), stored.tenancyId(),
            stored.component(), stored.amount(), stored.allocatedAmount(), stored.dueDate(),
            false, stored.allocated()));
    }

    public Stored find(UUID invoiceId) {
        return invoices.get(invoiceId);
    }

    public BigDecimal settled(UUID invoiceId) {
        return invoices.get(invoiceId).allocatedAmount();
    }

    /** What has been settled across every invoice of one tenancy carrying that component. */
    public BigDecimal settledFor(UUID tenancyId, Component component) {
        return invoices.values().stream()
            .filter(stored -> stored.tenancyId().equals(tenancyId))
            .filter(stored -> stored.component() == component)
            .map(Stored::allocatedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public List<Invoice> openInvoices(UUID workspaceId, UUID tenancyId) {
        var open = new ArrayList<Invoice>();
        invoices.forEach((invoiceId, stored) -> {
            if (stored.workspaceId().equals(workspaceId) && stored.tenancyId().equals(tenancyId)
                && stored.active() && stored.owed().signum() > 0) {
                open.add(new Invoice(invoiceId, stored.component(), stored.dueDate(),
                    stored.owed()));
            }
        });
        return open;
    }

    /**
     * Mirrors the statement this stands in for, which moves the amount and writes the flag at once:
     *
     * <pre>set allocated_amount = allocated_amount + ?, allocated = allocated_amount + ? &gt;= amount</pre>
     *
     * <p>Both sides of that SQL read the pre-update {@code allocated_amount}, so the flag is
     * computed from the old value plus this amount — which is what is done here, deliberately,
     * rather than from the value just stored.
     */
    @Override
    public void applyAllocation(UUID workspaceId, UUID invoiceId, BigDecimal amount) {
        Stored stored = invoices.get(invoiceId);
        if (stored == null || !stored.workspaceId().equals(workspaceId)) {
            return;
        }
        BigDecimal settledAfter = stored.allocatedAmount().add(amount);
        invoices.put(invoiceId, new Stored(stored.workspaceId(), stored.tenancyId(),
            stored.component(), stored.amount(), settledAfter, stored.dueDate(), stored.active(),
            settledAfter.compareTo(stored.amount()) >= 0));
    }
}
