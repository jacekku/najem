package pl.najem.contracts.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Emitted at effective-minus-1-day only. Carries the component breakdown because deposit
 * valorization is computed on the rent component alone — a flat total would silently corrupt
 * every later valorization.
 */
public record RentChangeAppliedEvent(
        UUID workspaceId,
        UUID tenancyId,
        LocalDate effectiveFrom,
        BigDecimal newMonthlyTotal,
        BigDecimal newRent,
        BigDecimal newAdminFee,
        BigDecimal newMediaAdvance,
        String changeType) implements IntegrationEvent {
}
