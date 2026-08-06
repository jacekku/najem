package pl.najem.um.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.um.domain.Role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model answering "what may this Keycloak subject reach?". Roles come from NAJEM's own
 * projection, never from token claims (decision D1).
 *
 * <p>Kept as a service rather than folded into {@link MembershipProjection} because it is this
 * module's cross-module surface: reporting and the web layer hold it, and they should be asking a
 * question about access rather than reaching into a table. What changed is that the SQL moved out
 * from under it.
 */
@Service
@Transactional(readOnly = true)
public class WorkspaceAccess {

    /** @see MembershipProjection.Membership */
    public record Membership(UUID workspaceId, String name, Role role) {}

    private final MembershipProjection memberships;
    private final UserProjection users;

    public WorkspaceAccess(MembershipProjection memberships, UserProjection users) {
        this.memberships = memberships;
        this.users = users;
    }

    public List<Membership> forSubject(UUID keycloakSubject) {
        return memberships.ofSubject(keycloakSubject).stream()
            .map(WorkspaceAccess::asMembership).toList();
    }

    /**
     * The same list, reached by NAJEM user id rather than by Keycloak subject.
     *
     * <p>{@code /me} used to resolve the caller to a user id, then turn that back into a subject via
     * {@link #subjectOf}, purely because {@link #forSubject} was the only way in. Two lookups and a
     * round trip through the identity provider's notion of a person to answer a question about ours.
     */
    public List<Membership> membershipsOfUser(UUID userId) {
        return memberships.ofUser(userId).stream().map(WorkspaceAccess::asMembership).toList();
    }

    /**
     * The one read that decides something, and the reason {@link MembershipProjection}'s javadoc
     * takes an explicit exception to rule A7: {@code ActingCaller} answers {@code @PreAuthorize}
     * from here, so authorization reads a derived store.
     */
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
        return users.subjectOf(userId);
    }

    private static Membership asMembership(MembershipProjection.Membership m) {
        return new Membership(m.workspaceId(), m.name(), m.role());
    }
}
