package pl.najem.um.application;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.Role;
import pl.najem.um.domain.Workspace;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Who belongs to an agency and at what role.
 *
 * <p>Both rules — you must already be a member, and the last ADMIN may not be demoted or removed —
 * used to be three SQL queries issued immediately before this service loaded the same workspace's
 * stream for its version. They now come off {@link Workspace}, which is the thing that holds them.
 * One round trip instead of four, but that is a side effect rather than the reason: the reason is
 * that the question had two possible answers and this was trusting the derived one.
 */
@Service
@Transactional
public class MembershipService {

    private final EventStore store;
    private final MembershipProjection memberships;

    public MembershipService(EventStore store, MembershipProjection memberships) {
        this.store = store;
        this.memberships = memberships;
    }

    @PreAuthorize("@caller.isAdminOf(#workspaceId)")
    public void changeRole(UUID workspaceId, UUID userId, Role role, LocalDate on) {
        var stream = store.load(workspaceId, "Workspace");
        var events = Workspace.from(UmStreams.workspaceEvents(stream)).changeRole(userId, role, on);
        store.append(workspaceId, "Workspace", stream.version(), events, List.of());
        memberships.changeRole(workspaceId, userId, role);
    }

    @PreAuthorize("@caller.isAdminOf(#workspaceId)")
    public void remove(UUID workspaceId, UUID userId, LocalDate on) {
        var stream = store.load(workspaceId, "Workspace");
        var events = Workspace.from(UmStreams.workspaceEvents(stream)).removeMember(userId, on);
        store.append(workspaceId, "Workspace", stream.version(), events, List.of());
        // A hard delete, and safe now that it is one: the membership record is the stream, so this
        // row destroys nothing that cannot be rebuilt from MemberJoined/MemberRemoved.
        memberships.remove(workspaceId, userId);
    }
}
