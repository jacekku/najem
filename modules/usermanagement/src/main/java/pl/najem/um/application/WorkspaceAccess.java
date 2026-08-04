package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.um.domain.Role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model answering "what may this Keycloak subject reach?". Roles come from NAJEM's own
 * projection, never from token claims (decision D1).
 */
@Service
@Transactional(readOnly = true)
public class WorkspaceAccess {

    public record Membership(UUID workspaceId, Role role) {}

    private final JdbcTemplate jdbc;

    public WorkspaceAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Membership> forSubject(UUID keycloakSubject) {
        return jdbc.query("""
            select m.workspace_id, m.role
            from um_membership m join um_user u on u.user_id = m.user_id
            where u.keycloak_subject = ?
            order by m.joined_on, m.workspace_id
            """, (rs, i) -> new Membership(rs.getObject(1, UUID.class), Role.valueOf(rs.getString(2))),
            keycloakSubject);
    }

    public Optional<Role> roleIn(UUID keycloakSubject, UUID workspaceId) {
        return forSubject(keycloakSubject).stream()
            .filter(m -> m.workspaceId().equals(workspaceId))
            .map(Membership::role)
            .findFirst();
    }

    public boolean canAccess(UUID keycloakSubject, UUID workspaceId) {
        return roleIn(keycloakSubject, workspaceId).isPresent();
    }

    public Optional<UUID> subjectOf(UUID userId) {
        return jdbc.query("select keycloak_subject from um_user where user_id = ?",
            (rs, i) -> rs.getObject(1, UUID.class), userId).stream().findFirst();
    }
}
