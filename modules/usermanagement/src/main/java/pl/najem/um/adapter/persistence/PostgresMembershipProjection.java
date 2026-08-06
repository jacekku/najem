package pl.najem.um.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.um.application.MembershipProjection;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresMembershipProjection implements MembershipProjection {

    private final JdbcTemplate jdbc;

    public PostgresMembershipProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upsert, because acceptance is the one path that can run twice for the same pair: an invitation
     * accepted by somebody who is already a member re-seats them at the invited role rather than
     * colliding on the primary key.
     */
    @Override
    public void join(UUID workspaceId, UUID userId, Role role, LocalDate on) {
        jdbc.update("""
            insert into um_membership(workspace_id, user_id, role, joined_on) values (?,?,?,?)
            on conflict (workspace_id, user_id) do update set role = excluded.role
            """, workspaceId, userId, role.name(), on);
    }

    @Override
    public void changeRole(UUID workspaceId, UUID userId, Role role) {
        jdbc.update("update um_membership set role = ? where workspace_id = ? and user_id = ?",
            role.name(), workspaceId, userId);
    }

    @Override
    public void remove(UUID workspaceId, UUID userId) {
        jdbc.update("delete from um_membership where workspace_id = ? and user_id = ?",
            workspaceId, userId);
    }

    @Override
    public Optional<Role> roleOf(UUID workspaceId, UUID userId) {
        return jdbc.query("select role from um_membership where workspace_id = ? and user_id = ?",
            (rs, i) -> Role.valueOf(rs.getString(1)), workspaceId, userId).stream().findFirst();
    }

    @Override
    public List<Membership> ofUser(UUID userId) {
        return jdbc.query("""
            select m.workspace_id, w.name, m.role
            from um_membership m
              join um_workspace w on w.workspace_id = m.workspace_id
            where m.user_id = ?
            order by m.joined_on, m.workspace_id
            """, MEMBERSHIP, userId);
    }

    @Override
    public List<Membership> ofSubject(UUID keycloakSubject) {
        return jdbc.query("""
            select m.workspace_id, w.name, m.role
            from um_membership m
              join um_user u on u.user_id = m.user_id
              join um_workspace w on w.workspace_id = m.workspace_id
            where u.keycloak_subject = ?
            order by m.joined_on, m.workspace_id
            """, MEMBERSHIP, keycloakSubject);
    }

    @Override
    public Map<UUID, Role> rolesIn(UUID workspaceId) {
        Map<UUID, Role> roles = new HashMap<>();
        jdbc.query("select user_id, role from um_membership where workspace_id = ?",
            rs -> { roles.put(rs.getObject(1, UUID.class), Role.valueOf(rs.getString(2))); },
            workspaceId);
        return roles;
    }

    private static final org.springframework.jdbc.core.RowMapper<Membership> MEMBERSHIP =
        (rs, i) -> new Membership(rs.getObject(1, UUID.class), rs.getString(2),
            Role.valueOf(rs.getString(3)));
}
