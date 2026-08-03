package pl.najem.um.domain;

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

    public static List<Object> register(UUID userId, UUID keycloakSubject, LocalDate on) {
        if (keycloakSubject == null) {
            throw new IllegalArgumentException("keycloak subject is required");
        }
        return List.of(new UserRegistered(userId, keycloakSubject, on));
    }

    public List<Object> linkContact(UUID contactId, LocalDate on) {
        if (this.contactId != null) {
            throw new IllegalStateException("user is already linked to a contact");
        }
        return List.of(new UserLinkedToContact(id, contactId, on));
    }

    public static User from(List<Object> events) {
        var user = new User();
        events.forEach(user::apply);
        return user;
    }

    private void apply(Object event) {
        if (event instanceof UserRegistered e) {
            id = e.userId();
            keycloakSubject = e.keycloakSubject();
        } else if (event instanceof UserLinkedToContact e) {
            contactId = e.contactId();
        }
    }

    public UUID id() { return id; }

    public UUID keycloakSubject() { return keycloakSubject; }

    public UUID contactId() { return contactId; }
}
