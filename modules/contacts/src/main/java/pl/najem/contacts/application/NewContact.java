package pl.najem.contacts.application;

import java.time.LocalDate;
import java.util.UUID;

public record NewContact(UUID workspaceId, ContactDetails details, String lawfulBasis,
                         LocalDate infoClauseServedAt, LocalDate retainUntil) {
}
