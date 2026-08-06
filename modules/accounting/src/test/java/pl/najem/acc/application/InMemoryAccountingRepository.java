package pl.najem.acc.application;

import pl.najem.acc.domain.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An {@link AccountingRepository} kept in a list, in the order the allocations were written. */
public class InMemoryAccountingRepository implements AccountingRepository {

    /**
     * One allocation line: which payment met which invoice, and for how much.
     *
     * <p>{@code reversed} is stored rather than the line being removed, as the update marks it: what
     * the books did before they were corrected is part of the record, and a fake that deleted the
     * row could not tell "undone" from "never happened".
     */
    public record Allocation(UUID allocationId, UUID workspaceId, UUID paymentId, UUID invoiceId,
                             UUID tenancyId, Component component, BigDecimal amount,
                             LocalDate dueDate, boolean reversed) {}

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
            component, amount, dueDate, false));
    }

    /** Ordered by invoice, as the query is, so an unwind moves money in a reproducible order. */
    @Override
    public List<LiveAllocation> liveAllocations(UUID workspaceId, UUID paymentId) {
        return allocations.stream()
            .filter(allocation -> allocation.workspaceId().equals(workspaceId))
            .filter(allocation -> allocation.paymentId().equals(paymentId))
            .filter(allocation -> !allocation.reversed())
            .map(allocation -> new LiveAllocation(allocation.invoiceId(), allocation.tenancyId(),
                allocation.amount()))
            .sorted(java.util.Comparator.comparing(allocation -> allocation.invoiceId().toString()))
            .toList();
    }

    @Override
    public void markReversed(UUID workspaceId, UUID paymentId) {
        allocations.replaceAll(allocation ->
            allocation.workspaceId().equals(workspaceId) && allocation.paymentId().equals(paymentId)
                && !allocation.reversed()
                ? new Allocation(allocation.allocationId(), allocation.workspaceId(),
                    allocation.paymentId(), allocation.invoiceId(), allocation.tenancyId(),
                    allocation.component(), allocation.amount(), allocation.dueDate(), true)
                : allocation);
    }
}
