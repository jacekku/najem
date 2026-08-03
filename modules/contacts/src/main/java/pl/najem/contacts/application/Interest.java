package pl.najem.contacts.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Interest(UUID interestId, UUID contactId, UUID unitId,
                       BigDecimal willingToPay, LocalDate desiredStart, String status) {
}
