package pl.najem.contracts.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TenancyActivatedEvent(
        UUID tenancyId,
        UUID unitId,
        LocalDate startDate,
        BigDecimal monthlyRent,
        String paymentReference) implements IntegrationEvent {
}
