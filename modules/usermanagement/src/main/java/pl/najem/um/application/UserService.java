package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public UserService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID register(UUID keycloakSubject, LocalDate on) {
        UUID userId = UUID.randomUUID();
        store.append(userId, "User", 0, User.register(userId, keycloakSubject, on), List.of());
        jdbc.update("insert into um_user(user_id, keycloak_subject, registered_on) values (?,?,?)",
            userId, keycloakSubject, on);
        return userId;
    }

    public void linkContact(UUID userId, UUID contactId, LocalDate on) {
        var stream = store.load(userId, "User");
        var user = User.from(stream.events());
        store.append(userId, "User", stream.version(), user.linkContact(contactId, on), List.of());
        jdbc.update("update um_user set contact_id = ? where user_id = ?", contactId, userId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findBySubject(UUID keycloakSubject) {
        return jdbc.query("select user_id from um_user where keycloak_subject = ?",
            (rs, i) -> rs.getObject(1, UUID.class), keycloakSubject).stream().findFirst();
    }
}
