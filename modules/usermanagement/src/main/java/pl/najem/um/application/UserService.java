package pl.najem.um.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class UserService {

    private final EventStore store;
    private final UserProjection users;

    public UserService(EventStore store, UserProjection users) {
        this.store = store;
        this.users = users;
    }

    public UUID register(UUID keycloakSubject, LocalDate on) {
        UUID userId = UUID.randomUUID();
        store.append(userId, "User", 0, User.register(userId, keycloakSubject, on), List.of());
        users.register(userId, keycloakSubject, on);
        return userId;
    }

    public void linkContact(UUID userId, UUID contactId, LocalDate on) {
        var stream = store.load(userId, "User");
        var user = User.from(UmStreams.userEvents(stream));
        store.append(userId, "User", stream.version(), user.linkContact(contactId, on), List.of());
        users.linkContact(userId, contactId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findBySubject(UUID keycloakSubject) {
        return users.findBySubject(keycloakSubject);
    }
}
