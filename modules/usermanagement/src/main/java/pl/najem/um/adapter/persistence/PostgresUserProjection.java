package pl.najem.um.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.um.application.UserProjection;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresUserProjection implements UserProjection {

    private final JdbcTemplate jdbc;

    public PostgresUserProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(UUID userId, UUID keycloakSubject, LocalDate on) {
        jdbc.update("insert into um_user(user_id, keycloak_subject, registered_on) values (?,?,?)",
            userId, keycloakSubject, on);
    }

    @Override
    public void linkContact(UUID userId, UUID contactId) {
        jdbc.update("update um_user set contact_id = ? where user_id = ?", contactId, userId);
    }

    @Override
    public Optional<UUID> findBySubject(UUID keycloakSubject) {
        return jdbc.query("select user_id from um_user where keycloak_subject = ?",
            (rs, i) -> rs.getObject(1, UUID.class), keycloakSubject).stream().findFirst();
    }

    @Override
    public Optional<UUID> subjectOf(UUID userId) {
        return jdbc.query("select keycloak_subject from um_user where user_id = ?",
            (rs, i) -> rs.getObject(1, UUID.class), userId).stream().findFirst();
    }

    /**
     * Two absences, one answer. A row that does not exist and a row whose {@code contact_id} is
     * null both yield empty — {@code rs.getObject} returns null for the second, and
     * {@link Optional#ofNullable} folds it into the first. Distinguishing them would tell a caller
     * whether an account exists, which is not a question a name lookup should answer.
     */
    @Override
    public Optional<UUID> contactOf(UUID userId) {
        return jdbc.query("select contact_id from um_user where user_id = ?",
                (rs, i) -> rs.getObject(1, UUID.class), userId).stream()
            .filter(java.util.Objects::nonNull)
            .findFirst();
    }

    @Override
    public boolean exists(UUID userId) {
        Integer count = jdbc.queryForObject("select count(*) from um_user where user_id = ?",
            Integer.class, userId);
        return count != null && count > 0;
    }
}
