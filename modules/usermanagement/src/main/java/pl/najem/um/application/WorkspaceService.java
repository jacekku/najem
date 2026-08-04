package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.Role;
import pl.najem.um.domain.Workspace;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Transactional
public class WorkspaceService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public WorkspaceService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    /**
     * Creates a workspace and makes its creator the first ADMIN, in one transaction.
     *
     * <p>The membership is not optional. NAJEM is invite-only, and only an ADMIN can invite — a
     * workspace created without one could never be joined by anybody (coordinator ruling, seq 56).
     */
    public UUID create(String name, UUID creatorUserId, LocalDate on) {
        requireExistingUser(creatorUserId);
        UUID workspaceId = UUID.randomUUID();
        var events = Workspace.create(workspaceId, name, on);
        var created = Workspace.from(events);
        store.append(workspaceId, "Workspace", 0, events,
            List.of(new WorkspaceCreatedEvent(workspaceId, created.name())));
        jdbc.update("insert into um_workspace(workspace_id, name, created_on) values (?,?,?)",
            workspaceId, created.name(), on);
        jdbc.update("insert into um_membership(workspace_id, user_id, role, joined_on) values (?,?,?,?)",
            workspaceId, creatorUserId, Role.ADMIN.name(), on);
        return workspaceId;
    }

    public void rename(UUID workspaceId, String name, LocalDate on) {
        var stream = store.load(workspaceId, "Workspace");
        var workspace = Workspace.from(stream.events());
        var events = workspace.rename(name, on);
        store.append(workspaceId, "Workspace", stream.version(), events, List.of());
        var renamed = Workspace.from(Stream.concat(stream.events().stream(), events.stream()).toList());
        jdbc.update("update um_workspace set name = ? where workspace_id = ?", renamed.name(), workspaceId);
    }

    private void requireExistingUser(UUID userId) {
        Integer count = jdbc.queryForObject("select count(*) from um_user where user_id = ?",
            Integer.class, userId);
        if (count == null || count == 0) {
            throw new IllegalStateException("creator is not a registered user: " + userId);
        }
    }
}
