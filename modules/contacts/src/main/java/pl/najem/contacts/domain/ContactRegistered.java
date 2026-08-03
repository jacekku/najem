package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record ContactRegistered(UUID workspaceId, UUID contactId, String lawfulBasis,
                                LocalDate infoClauseServedAt, LocalDate retainUntil) {
}
