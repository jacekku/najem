package pl.najem.um.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code um_user}: which NAJEM account belongs to which Keycloak subject, and which contact it is
 * linked to. Derivable from the {@code User} stream ({@code UserRegistered}, {@code UserLinkedToContact}),
 * so a projection.
 *
 * <p>Holds no personal data — Keycloak owns credentials and profile (D1), Contacts owns person data.
 */
public interface UserProjection {

    void register(UUID userId, UUID keycloakSubject, LocalDate on);

    void linkContact(UUID userId, UUID contactId);

    Optional<UUID> findBySubject(UUID keycloakSubject);

    Optional<UUID> subjectOf(UUID userId);

    boolean exists(UUID userId);
}
