package pl.najem.acc.domain;

import java.util.UUID;

/** An unpaid charge withdrawn from the ledger. The row remains, inactive — never deleted. */
public record ChargeDeactivated(UUID chargeId, UUID tenancyId, String reason) {
}
