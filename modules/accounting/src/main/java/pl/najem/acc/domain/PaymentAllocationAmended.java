package pl.najem.acc.domain;

import java.util.UUID;

/**
 * The money is real; it belonged to a different tenancy than the one it was settled against. The
 * wrong tenancy's charges reopen and the right tenancy's settle.
 */
public record PaymentAllocationAmended(UUID paymentId, UUID tenancyId, String reason) {
}
