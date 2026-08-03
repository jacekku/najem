package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record UserLinkedToContact(UUID userId, UUID contactId, LocalDate linkedOn) {}
