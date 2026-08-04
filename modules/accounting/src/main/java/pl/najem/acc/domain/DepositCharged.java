package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The deposit as agreed, with the multiple snapshotted at activation. The rent it is a multiple of
 * will move over a tenancy; the multiple agreed in the contract will not, and valorization at
 * return works from the snapshot rather than from today's arithmetic.
 */
public record DepositCharged(UUID depositId, UUID tenancyId, UUID chargeId, BigDecimal amount,
                             BigDecimal multiplier, BigDecimal rentAtCharge, String legalForm) {
}
