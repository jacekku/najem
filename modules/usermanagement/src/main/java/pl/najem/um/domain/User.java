package pl.najem.um.domain;

import pl.najem.um.domain.events.UserEvent;
import pl.najem.um.domain.events.UserLinkedToContact;
import pl.najem.um.domain.events.UserRegistered;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Event-sourced aggregate; stream type "User". Holds NO personal data — Keycloak owns
 * credentials and profile (decision D1), Contacts owns person data (seq 14 settlement).
 */
public class User {

    private UUID id;
    private UUID keycloakSubject;
    private UUID contactId;

    private User() {}

    public static List<UserEvent> register(UUID userId, UUID keycloakSubject, LocalDate on) {
        if (keycloakSubject == null) {
            throw new IllegalArgumentException("keycloak subject is required");
        }
        return List.of(new UserRegistered(userId, keycloakSubject, on));
    }

    /**
     * Applied to this instance before it is returned, as {@link Workspace#decided} explains. Without
     * it, calling this twice on one instance would link a second contact — the guard above reads
     * state that the first call never updated.
     */
    public List<UserEvent> linkContact(UUID contactId, LocalDate on) {
        if (this.contactId != null) {
            throw new IllegalStateException("user is already linked to a contact");
        }
        var decided = List.<UserEvent>of(new UserLinkedToContact(id, contactId, on));
        decided.forEach(this::apply);
        return decided;
    }

    public static User from(List<UserEvent> events) {
        var user = new User();
        events.forEach(user::apply);
        return user;
    }

    /** Exhaustive, no {@code default}: see {@link Workspace#apply} for why that is the point. */
    private void apply(UserEvent event) {
        switch (event) {
            case UserRegistered e -> {
                id = e.userId();
                keycloakSubject = e.keycloakSubject();
            }
            case UserLinkedToContact e -> contactId = e.contactId();
        }
    }

    public UUID id() { return id; }

    public UUID keycloakSubject() { return keycloakSubject; }

    public UUID contactId() { return contactId; }
}
