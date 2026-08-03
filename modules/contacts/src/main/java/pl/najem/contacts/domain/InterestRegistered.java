package pl.najem.contacts.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InterestRegistered(UUID workspaceId, UUID interestId, UUID contactId, UUID unitId,
                                 BigDecimal willingToPay, LocalDate desiredStart) {
}
