package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record ContactDetailsCorrected(UUID workspaceId, UUID contactId, LocalDate correctedOn) {
}
