package pl.najem.um.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory {@link UserProjection}. Stands in for {@code um_user}. */
public class InMemoryUsers implements UserProjection {

    record Row(UUID keycloakSubject, UUID contactId, LocalDate registeredOn) {}

    final Map<UUID, Row> rows = new LinkedHashMap<>();

    @Override
    public void register(UUID userId, UUID keycloakSubject, LocalDate on) {
        // um_user.keycloak_subject is unique, and the SQL insert would fail. A fake that silently
        // overwrote would let a test register two users for one subject and never notice that
        // findBySubject had become ambiguous.
        rows.values().stream().filter(r -> r.keycloakSubject().equals(keycloakSubject)).findAny()
            .ifPresent(r -> { throw new IllegalStateException("keycloak subject already registered"); });
        rows.put(userId, new Row(keycloakSubject, null, on));
    }

    @Override
    public void linkContact(UUID userId, UUID contactId) {
        var row = rows.get(userId);
        if (row == null) {
            return; // an update matching no row, as the statement does
        }
        rows.put(userId, new Row(row.keycloakSubject(), contactId, row.registeredOn()));
    }

    @Override
    public Optional<UUID> findBySubject(UUID keycloakSubject) {
        return rows.entrySet().stream()
            .filter(e -> e.getValue().keycloakSubject().equals(keycloakSubject))
            .map(Map.Entry::getKey)
            .findFirst();
    }

    @Override
    public Optional<UUID> subjectOf(UUID userId) {
        return Optional.ofNullable(rows.get(userId)).map(Row::keycloakSubject);
    }

    @Override
    public boolean exists(UUID userId) {
        return rows.containsKey(userId);
    }
}
