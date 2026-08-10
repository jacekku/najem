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

    /**
     * The contact this account was linked to, if it ever was.
     *
     * <p>The read side of {@link #linkContact}, which had a writer and no reader — {@code contact_id}
     * was being maintained and could not be asked about, so the profile and the agency's team list
     * both had a name sitting in the database and no way to reach it.
     *
     * <p>Empty means unlinked, which is an ordinary state: an account registered before it was ever
     * matched to a person has no contact, and neither does the platform operator. It is emphatically
     * NOT "look somewhere else" — this module holds no personal data of its own (D1), so an empty
     * answer here is the end of the road for a name and a screen must render the absence rather than
     * invent a placeholder.
     */
    Optional<UUID> contactOf(UUID userId);

    boolean exists(UUID userId);
}
