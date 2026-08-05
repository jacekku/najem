package pl.najem.acc.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A deposit about to be recorded, with the figures that were true when it was agreed.
 *
 * <p>{@code rentAtCharge} and {@code multiplier} are snapshots on purpose. Valorization at return
 * works from the multiple agreed at activation, so recomputing either from today's figures would
 * re-price the contract every time the rent moved.
 */
public record DepositToCharge(UUID depositId, UUID invoiceId, String legalForm, BigDecimal amount,
                              BigDecimal rentAtCharge, BigDecimal multiplier, LocalDate chargedOn) {
}
