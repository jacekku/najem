package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record RetentionHoldReleased(UUID workspaceId, UUID contactId, String reason, LocalDate releasedOn) {
}
