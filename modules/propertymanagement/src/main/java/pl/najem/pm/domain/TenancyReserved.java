package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TenancyReserved(UUID tenancyId, UUID unitId, LocalDate startDate,
                              BigDecimal monthlyRent, String paymentReference) {
}
