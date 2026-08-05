package pl.najem.acc.application;

import pl.najem.acc.domain.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An {@link AccountingRepository} kept in a list, in the order the allocations were written. */
public class InMemoryAccountingRepository implements AccountingRepository {

    /** One allocation line: which payment met which invoice, and for how much. */
    public record Allocation(UUID allocationId, UUID workspaceId, UUID paymentId, UUID invoiceId,
                             UUID tenancyId, Component component, BigDecimal amount,
                             LocalDate dueDate) {}

    private final List<Allocation> allocations = new ArrayList<>();

    public List<Allocation> all() {
        return List.copyOf(allocations);
    }

    /** What one payment came to rest against in total. */
    public BigDecimal totalAllocatedTo(UUID paymentId) {
        return allocations.stream()
            .filter(allocation -> allocation.paymentId().equals(paymentId))
            .map(Allocation::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public void recordAllocation(UUID allocationId, UUID workspaceId, UUID paymentId, UUID invoiceId,
                                 UUID tenancyId, Component component, BigDecimal amount,
                                 LocalDate dueDate) {
        allocations.add(new Allocation(allocationId, workspaceId, paymentId, invoiceId, tenancyId,
            component, amount, dueDate));
    }
}
