package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Task 4 widens this to the full v1.1 agreement (legalForm, term, breakdown, deposit, contacts). */
public record TenancyReserved(UUID workspaceId, UUID tenancyId, UUID unitId, LocalDate startDate,
                              LocalDate endDate, BigDecimal monthlyRent, String paymentReference) {
}
