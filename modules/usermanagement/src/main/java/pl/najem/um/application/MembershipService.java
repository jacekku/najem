package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.MemberRemoved;
import pl.najem.um.domain.MemberRoleChanged;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MembershipService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public MembershipService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public void changeRole(UUID workspaceId, UUID userId, Role role, LocalDate on) {
        requireMember(workspaceId, userId);
        if (role != Role.ADMIN) {
            requireAnotherAdmin(workspaceId, userId);
        }
        var stream = store.load(workspaceId, "Workspace");
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new MemberRoleChanged(workspaceId, userId, role, on)), List.of());
        jdbc.update("update um_membership set role = ? where workspace_id = ? and user_id = ?",
            role.name(), workspaceId, userId);
    }

    public void remove(UUID workspaceId, UUID userId, LocalDate on) {
        requireMember(workspaceId, userId);
        requireAnotherAdmin(workspaceId, userId);
        var stream = store.load(workspaceId, "Workspace");
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new MemberRemoved(workspaceId, userId, on)), List.of());
        jdbc.update("delete from um_membership where workspace_id = ? and user_id = ?", workspaceId, userId);
    }

    private void requireMember(UUID workspaceId, UUID userId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from um_membership where workspace_id = ? and user_id = ?",
            Integer.class, workspaceId, userId);
        if (count == null || count == 0) {
            throw new IllegalStateException("user is not a member of this workspace");
        }
    }

    /** A workspace must always keep at least one ADMIN, or nobody can ever invite into it again. */
    private void requireAnotherAdmin(UUID workspaceId, UUID userId) {
        Boolean isAdmin = jdbc.queryForObject(
            "select role = 'ADMIN' from um_membership where workspace_id = ? and user_id = ?",
            Boolean.class, workspaceId, userId);
        if (!Boolean.TRUE.equals(isAdmin)) {
            return;
        }
        Integer otherAdmins = jdbc.queryForObject("""
            select count(*) from um_membership
            where workspace_id = ? and role = 'ADMIN' and user_id <> ?
            """, Integer.class, workspaceId, userId);
        if (otherAdmins == null || otherAdmins == 0) {
            throw new IllegalStateException("workspace must keep at least one admin");
        }
    }
}
