package pl.najem.contracts.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * v2 (CCR seq 55): monthly figure is a mandatory total with an optional contractual breakdown.
 * componentSplitInContract is explicit — "no split" (collapse rule: everything is rent, fully
 * taxable and valorizable) and "split with zero adminFee" are legally different facts.
 * legalForm is a String, not an enum: enums in shared contracts are a versioning trap.
 */
public record TenancyActivatedEvent(
        UUID workspaceId,
        UUID tenancyId,
        UUID unitId,
        LocalDate startDate,
        BigDecimal monthlyTotal,
        boolean componentSplitInContract,
        BigDecimal rent,
        BigDecimal adminFee,
        BigDecimal mediaAdvance,
        String legalForm,
        BigDecimal depositAmount,
        String paymentReference) implements IntegrationEvent {
}
