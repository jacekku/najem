package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ChargePosted(UUID chargeId, UUID tenancyId, String component,
                           BigDecimal amount, LocalDate dueDate) {
}
