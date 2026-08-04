package pl.najem.acc.domain;

import java.util.UUID;

/**
 * A manager's judgement that this money was never a tenant's: an outgoing utility debit, a bank
 * fee, an owner's own transfer. It leaves the reconciliation queue by being classified rather than
 * by being matched to something it isn't.
 */
public record PaymentMarkedNonTenant(UUID paymentId, String reason) {
}
