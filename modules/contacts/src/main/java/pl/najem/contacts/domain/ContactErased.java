package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record ContactErased(UUID workspaceId, UUID contactId, LocalDate erasedOn) {
}
