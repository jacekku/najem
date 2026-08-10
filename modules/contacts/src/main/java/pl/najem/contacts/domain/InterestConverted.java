package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The lead signed. Carries {@code tenancyId} because the link from a lead to what they became is the
 * fact worth keeping, and it exists nowhere else — PM's stream names contact ids but not the
 * interest the manager actually chose.
 */
public record InterestConverted(UUID workspaceId, UUID interestId, UUID contactId, UUID unitId,
                                UUID tenancyId, LocalDate convertedOn) {
}
