package pl.najem.acc.application;

import pl.najem.acc.domain.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The allocation ledger — the record of which payment met which invoice, and for how much.
 *
 * <p>An allocation belongs to neither the payment nor the invoice; it is the line between them, so
 * it is kept here rather than folded into either side's repository.
 */
public interface AccountingRepository {

    /** Writes one allocation line. The identifier is the caller's, so the write stays replayable. */
    void recordAllocation(UUID allocationId, UUID workspaceId, UUID paymentId, UUID invoiceId,
                          UUID tenancyId, Component component, BigDecimal amount, LocalDate dueDate);
}
