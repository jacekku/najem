package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Hard reservation = agreement signed. Contacts are referenced by id — no PII in PM. */
public record ReserveTenancy(UUID tenancyId, UUID workspaceId, UUID unitId,
                             List<UUID> tenantContactIds, List<UUID> guarantorContactIds,
                             LocalDate startDate, Term term, LegalForm legalForm,
                             MonthlyAmount monthly, int rentDay, BigDecimal depositAmount,
                             String paymentReference) {
}
