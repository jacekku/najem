package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record InterestWithdrawn(UUID workspaceId, UUID interestId, UUID contactId, LocalDate withdrawnOn) {
}
