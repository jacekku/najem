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

    /**
     * Carries the agency's name, not only its id. Without it the only workspace-identifying thing
     * a screen can show is the UUID — which the interface must not show, because an agency has a
     * name and the people using this are estate agents rather than operators. It is also what
     * gives "the seam resolved to the right agency" a user-visible observable to assert on.
     */
    public record Membership(UUID workspaceId, String name, Role role) {}

    private final JdbcTemplate jdbc;

    public WorkspaceAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Membership> forSubject(UUID keycloakSubject) {
        return jdbc.query("""
            select m.workspace_id, w.name, m.role
            from um_membership m
              join um_user u on u.user_id = m.user_id
              join um_workspace w on w.workspace_id = m.workspace_id
            where u.keycloak_subject = ?
            order by m.joined_on, m.workspace_id
            """, (rs, i) -> new Membership(
                rs.getObject(1, UUID.class), rs.getString(2), Role.valueOf(rs.getString(3))),
            keycloakSubject);
    }

    /**
     * The same list, reached by NAJEM user id rather than by Keycloak subject.
     *
     * <p>{@code /me} used to resolve the caller to a user id, then turn that back into a subject via
     * {@link #subjectOf}, purely because {@link #forSubject} was the only way in. Two lookups and a
     * round trip through the identity provider's notion of a person to answer a question about ours.
     */
    public List<Membership> membershipsOfUser(UUID userId) {
        return jdbc.query("""
            select m.workspace_id, w.name, m.role
            from um_membership m
              join um_workspace w on w.workspace_id = m.workspace_id
            where m.user_id = ?
            order by m.joined_on, m.workspace_id
            """, (rs, i) -> new Membership(
                rs.getObject(1, UUID.class), rs.getString(2), Role.valueOf(rs.getString(3))),
            userId);
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
