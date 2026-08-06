package pl.najem.um.application;

import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of who belongs to which agency: {@code um_membership}, joined to {@code um_workspace}
 * for the name a screen can show.
 *
 * <p><b>{@code Projection}, not {@code Repository}</b> (rule A7). Every row here is derivable from
 * the {@code Workspace} stream — {@code MemberJoined}, {@code MemberRoleChanged}, {@code MemberRemoved}
 * — and the aggregate is what decides. This table can be dropped and rebuilt. That is also why
 * {@code remove} is a hard delete and not a soft one: the record is the stream, and a deleted row
 * destroys nothing.
 *
 * <p><b>Read to decide, which A7 says a projection is not for.</b> {@link ActingCaller} answers
 * {@code @PreAuthorize} from {@link #roleOf}, so authorization does read a derived store. Replaying
 * a workspace stream on every request is not viable, so the exception is taken knowingly and paid
 * for elsewhere: the projection is written in the same transaction as the append, and a test asserts
 * it agrees with the aggregate after an invite/accept/change-role/remove round trip. If those ever
 * diverge it is an authorization bug, and it should fail a build rather than a pentest.
 *
 * <p>No method here decides anything. It reports rows.
 */
public interface MembershipProjection {

    /** Carries the agency's name because a screen must not show a UUID; agencies have names. */
    record Membership(UUID workspaceId, String name, Role role) {}

    void join(UUID workspaceId, UUID userId, Role role, LocalDate on);

    void changeRole(UUID workspaceId, UUID userId, Role role);

    void remove(UUID workspaceId, UUID userId);

    Optional<Role> roleOf(UUID workspaceId, UUID userId);

    List<Membership> ofUser(UUID userId);

    /**
     * Everybody in one agency and what they hold.
     *
     * <p>Exists so the projection can be compared against the aggregate as a whole set. Reading it
     * member by member would let a row the aggregate does not know about go unnoticed, which is the
     * direction that matters here — an extra membership is somebody with access the record never
     * granted.
     */
    Map<UUID, Role> rolesIn(UUID workspaceId);

    List<Membership> ofSubject(UUID keycloakSubject);
}
