package pl.najem.contracts.events;

import java.time.LocalDate;
import java.util.UUID;

/** vacateDate is an input to accounting's deposit-settlement deadline (max(vacate, protocol) + 1 month). */
public record TenancyEndedEvent(
        UUID workspaceId,
        UUID tenancyId,
        UUID unitId,
        LocalDate endDate,
        LocalDate vacateDate,
        String reasonType) implements IntegrationEvent {
}
