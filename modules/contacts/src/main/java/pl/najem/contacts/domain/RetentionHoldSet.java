package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record RetentionHoldSet(UUID workspaceId, UUID contactId, String reason, LocalDate setOn) {
}
