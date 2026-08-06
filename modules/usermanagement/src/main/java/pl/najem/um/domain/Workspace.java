package pl.najem.um.domain;

import pl.najem.um.domain.events.InvitationAccepted;
import pl.najem.um.domain.events.InvitationRevoked;
import pl.najem.um.domain.events.MemberInvited;
import pl.najem.um.domain.events.MemberJoined;
import pl.najem.um.domain.events.MemberRemoved;
import pl.najem.um.domain.events.MemberRoleChanged;
import pl.najem.um.domain.events.WorkspaceCreated;
import pl.najem.um.domain.events.WorkspaceEvent;
import pl.najem.um.domain.events.WorkspaceRenamed;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Event-sourced aggregate; stream type "Workspace". The agency that owns properties, ledgers and
 * people, who belongs to it, and which invitations into it are still open.
 *
 * <p>Membership used to live only in {@code um_membership}, and every rule about it was a SQL query
 * in {@code MembershipService} — issued one line before the same service loaded this stream for its
 * version. That is the arrangement property-management retired {@code WorkspaceGuard} for: one
 * question with two answers, and the one being trusted was the derived one. The events were already
 * being written; nothing applied them.
 */
public class Workspace {

    /** A pending invitation, from the aggregate's point of view. */
    private record Invitation(Role role, LocalDate expiresOn) {}

    private UUID id;
    private String name;
    private final Map<UUID, Role> members = new LinkedHashMap<>();
    private final Map<UUID, Invitation> pending = new LinkedHashMap<>();

    private Workspace() {}

    /**
     * A new agency and its first ADMIN, together.
     *
     * <p>The membership is not optional. NAJEM is invite-only and only an ADMIN can invite, so a
     * workspace created without one could never be joined by anybody (coordinator ruling, seq 56).
     * It is emitted here rather than assembled by the caller so that the invariant is a property of
     * creating a workspace rather than of remembering to.
     */
    public static List<WorkspaceEvent> create(UUID workspaceId, String name, UUID founderUserId,
                                              LocalDate on) {
        requireName(name);
        if (founderUserId == null) {
            throw new IllegalArgumentException("a workspace must be created by somebody");
        }
        return List.of(
            new WorkspaceCreated(workspaceId, name.trim(), on),
            new MemberJoined(workspaceId, founderUserId, Role.ADMIN, on));
    }

    public List<WorkspaceEvent> rename(String newName, LocalDate on) {
        requireName(newName);
        return decided(new WorkspaceRenamed(id, newName.trim(), on));
    }

    public List<WorkspaceEvent> invite(UUID invitationId, Role role, UUID invitedByUserId,
                                       LocalDate on, LocalDate expiresOn) {
        if (expiresOn.isBefore(on)) {
            throw new IllegalArgumentException("invitation cannot expire before it is issued");
        }
        return decided(new MemberInvited(id, invitationId, role, invitedByUserId, on, expiresOn));
    }

    /**
     * Not-found and not-yours are deliberately the same answer: saying which it was confirms the
     * existence of an invitation in somebody else's agency. This aggregate only ever holds one
     * workspace's invitations, so "unknown here" covers both without the caller arranging it.
     */
    public List<WorkspaceEvent> revokeInvitation(UUID invitationId, LocalDate on) {
        requirePending(invitationId);
        return decided(new InvitationRevoked(id, invitationId, on));
    }

    /**
     * Whether this invitation may still be accepted, asked separately from accepting it.
     *
     * <p>Split out because acceptance provisions the invitee in Keycloak before it knows their NAJEM
     * user id, and the aggregate cannot produce the events until it has one. Without this, a spent
     * or expired invitation would be refused only after the identity provider had been called and
     * the failure would arrive as whatever went wrong next — for an already-accepted invitation,
     * "no recipient on record", because the address is deleted on acceptance. Wrong reason, and one
     * that reads like data corruption rather than a used link.
     */
    public void requireInvitationOpen(UUID invitationId, LocalDate on) {
        var invitation = requirePending(invitationId);
        if (on.isAfter(invitation.expiresOn())) {
            throw new IllegalStateException("invitation expired on " + invitation.expiresOn());
        }
    }

    /** Accepting is what creates a membership; see {@link MemberJoined} for why that is two events. */
    public List<WorkspaceEvent> acceptInvitation(UUID invitationId, UUID userId, LocalDate on) {
        requireInvitationOpen(invitationId, on);
        var invitation = requirePending(invitationId);
        return decided(
            new InvitationAccepted(id, invitationId, userId, on),
            new MemberJoined(id, userId, invitation.role(), on));
    }

    public List<WorkspaceEvent> changeRole(UUID userId, Role role, LocalDate on) {
        requireMember(userId);
        if (role != Role.ADMIN) {
            requireTheyAreNotTheLastAdmin(userId);
        }
        return decided(new MemberRoleChanged(id, userId, role, on));
    }

    public List<WorkspaceEvent> removeMember(UUID userId, LocalDate on) {
        requireMember(userId);
        requireTheyAreNotTheLastAdmin(userId);
        return decided(new MemberRemoved(id, userId, on));
    }

    public Optional<Role> roleOf(UUID userId) {
        return Optional.ofNullable(members.get(userId));
    }

    public Map<UUID, Role> members() {
        return Map.copyOf(members);
    }

    public static Workspace from(List<WorkspaceEvent> events) {
        var workspace = new Workspace();
        events.forEach(workspace::apply);
        return workspace;
    }

    /**
     * What a command decided, applied to this instance before it is handed back.
     *
     * <p>Every command goes through here, and that is the invariant worth stating: <b>an aggregate
     * that returns events without applying them is stale the moment it does.</b> A caller that
     * decides twice would make the second decision against the state before the first —
     * {@code invite} then {@code acceptInvitation} on one instance would not find the invitation it
     * had just issued, and {@code removeMember} twice would happily remove the same person again,
     * because the members map never heard about the first.
     *
     * <p>The events still go back to the caller, because only the caller can append them. If the
     * append then fails — a concurrency conflict, a rolled-back transaction — this instance is ahead
     * of the store, and is discarded rather than reused: a service loads a fresh one per operation.
     */
    private List<WorkspaceEvent> decided(WorkspaceEvent... events) {
        var decided = List.of(events);
        decided.forEach(this::apply);
        return decided;
    }

    /** Exhaustive on purpose: a new {@link WorkspaceEvent} does not compile until it is decided here. */
    private void apply(WorkspaceEvent event) {
        switch (event) {
            case WorkspaceCreated e -> {
                id = e.workspaceId();
                name = e.name();
            }
            case WorkspaceRenamed e -> name = e.name();
            case MemberInvited e -> pending.put(e.invitationId(), new Invitation(e.role(), e.expiresOn()));
            case InvitationRevoked e -> pending.remove(e.invitationId());
            case InvitationAccepted e -> pending.remove(e.invitationId());
            case MemberJoined e -> members.put(e.userId(), e.role());
            case MemberRoleChanged e -> members.put(e.userId(), e.role());
            case MemberRemoved e -> members.remove(e.userId());
        }
    }

    private Invitation requirePending(UUID invitationId) {
        var invitation = pending.get(invitationId);
        if (invitation == null) {
            throw new IllegalStateException(
                "no pending invitation " + invitationId + " in this workspace");
        }
        return invitation;
    }

    private void requireMember(UUID userId) {
        if (!members.containsKey(userId)) {
            throw new IllegalStateException("user is not a member of this workspace");
        }
    }

    /**
     * A workspace must always keep at least one ADMIN, or nobody can ever invite into it again.
     *
     * <p>Only bites when the subject <em>is</em> an ADMIN. Demoting or removing anybody else cannot
     * take the last one away, and refusing it because the count happens to be zero would refuse to
     * remove the only account an operator could later elevate — which is the wrong thing to do in
     * precisely the situation where the invariant is already broken.
     */
    private void requireTheyAreNotTheLastAdmin(UUID userId) {
        if (members.get(userId) != Role.ADMIN) {
            return;
        }
        boolean anotherAdmin = members.entrySet().stream()
            .anyMatch(m -> m.getValue() == Role.ADMIN && !m.getKey().equals(userId));
        if (!anotherAdmin) {
            throw new IllegalStateException("workspace must keep at least one admin");
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("workspace name must not be blank");
        }
    }

    public UUID id() { return id; }

    public String name() { return name; }
}
