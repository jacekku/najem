package pl.najem.um.domain.events;

/**
 * Everything that has ever happened to one agency. Stream type {@code "Workspace"}.
 *
 * <p>Sealed so that {@link pl.najem.um.domain.Workspace}'s {@code apply} can be an exhaustive
 * switch with no {@code default} branch. That is the point of the interface rather than a
 * tidiness preference: adding a member event and forgetting to fold it into the aggregate used to
 * compile, and five of the events below were in exactly that state — appended to this stream on
 * every invite, accept, revoke, role change and removal, and applied by nothing. Nobody noticed,
 * because nothing could notice. Now the compiler does.
 *
 * <p>Dropping {@code default} is only honest because {@code EventStore.load} keys on stream id
 * <em>and</em> type, so a foreign module's event cannot arrive here — see that method's javadoc for
 * the incident that seam was built for.
 *
 * <p><b>There is deliberately no shared {@code DomainEvent} supertype.</b> It would have to live in
 * {@code platform:eventstore} or {@code contracts}, and importing it would make this the first
 * package in the repository to break A3 — the domain imports nothing outside the JDK, currently
 * true in all four modules with zero exceptions. See {@code EventStore.append}'s TODO for what that
 * costs and what it would buy.
 */
public sealed interface WorkspaceEvent
    permits WorkspaceCreated, WorkspaceRenamed,
            MemberInvited, MemberJoined, MemberRoleChanged, MemberRemoved,
            InvitationAccepted, InvitationRevoked {
}
