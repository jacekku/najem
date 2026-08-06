package pl.najem.um.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.Role;
import pl.najem.um.domain.Workspace;

import java.time.LocalDate;
import java.util.List;

import java.util.UUID;

@Service
@Transactional
public class WorkspaceService {

    private final EventStore store;
    private final WorkspaceProjection workspaces;
    private final MembershipProjection memberships;
    private final UserProjection users;

    public WorkspaceService(EventStore store, WorkspaceProjection workspaces,
                            MembershipProjection memberships, UserProjection users) {
        this.store = store;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.users = users;
    }

    /**
     * Creates a workspace and makes its creator the first ADMIN, in one transaction.
     *
     * <p>The founding membership is decided by {@link Workspace#create} rather than assembled here,
     * so the invariant belongs to creating a workspace rather than to remembering to. Before that it
     * was a bare insert with no event behind it, which meant the founder was a member the stream had
     * no record of.
     */
    public UUID create(String name, UUID creatorUserId, LocalDate on) {
        if (!users.exists(creatorUserId)) {
            throw new IllegalStateException("creator is not a registered user: " + creatorUserId);
        }
        UUID workspaceId = UUID.randomUUID();
        var events = Workspace.create(workspaceId, name, creatorUserId, on);
        var created = Workspace.from(events);
        store.append(workspaceId, "Workspace", 0, events,
            List.of(new WorkspaceCreatedEvent(workspaceId, created.name())));
        workspaces.create(workspaceId, created.name(), on);
        memberships.join(workspaceId, creatorUserId, Role.ADMIN, on);
        return workspaceId;
    }

    public void rename(UUID workspaceId, String name, LocalDate on) {
        var stream = store.load(workspaceId, "Workspace");
        var workspace = Workspace.from(UmStreams.workspaceEvents(stream));
        var events = workspace.rename(name, on);
        store.append(workspaceId, "Workspace", stream.version(), events, List.of());
        // The aggregate applied its own event, so it already holds the trimmed name. This used to
        // replay the whole history concatenated with the new event to find that out.
        workspaces.rename(workspaceId, workspace.name());
    }
}
