package pl.najem.acc.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One settlement that still stands: this much of a payment is resting on this invoice.
 *
 * <p>The tenancy is carried because undoing the allocation changes what that tenancy owes, and the
 * arrears board has to be told. Reading it from the allocation rather than the invoice keeps the
 * unwind to one pass.
 */
public record LiveAllocation(UUID invoiceId, UUID tenancyId, BigDecimal amount) {
}
