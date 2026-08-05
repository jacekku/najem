package pl.najem.um.application;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import pl.najem.um.adapter.security.CurrentUser;
import pl.najem.um.domain.Role;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the acting user for a request.
 *
 * <p>There are three kinds of caller and they are not interchangeable. A machine client presents a
 * bearer token and arrives as a {@code Jwt}. A person who signed in through the browser arrives as
 * an {@code OidcUser} and reaches these methods with a {@code null} jwt — so they must be resolved
 * from the security context, or they would be mistaken for nobody. Only when there is genuinely no
 * caller at all does the configured platform operator apply.
 *
 * <p>With security disabled — local runs and the walking skeleton — there is no token,
 * and the only acceptable caller is the explicitly configured platform operator. If neither exists
 * the request is denied rather than silently allowed: an unauthenticated request must never end up
 * acting as somebody.
 */
@Component
public class WorkspaceCaller {

    private final CurrentUser currentUser;
    private final Optional<PlatformOperator> operator;
    private final UserService users;
    private final WorkspaceAccess access;

    public WorkspaceCaller(CurrentUser currentUser, Optional<PlatformOperator> operator,
                           UserService users, WorkspaceAccess access) {
        this.currentUser = currentUser;
        this.operator = operator;
        this.users = users;
        this.access = access;
    }

    /** Resolves the caller and enforces that they hold one of {@code allowed} in the workspace. */
    public UUID resolve(Jwt jwt, UUID workspaceId, Role... allowed) {
        if (jwt != null) {
            currentUser.requireRole(jwt, workspaceId, allowed);
            return currentUser.requireUserId(jwt);
        }
        return signedInSubject()
            .map(subject -> {
                requireRoleOf(subject, workspaceId, allowed);
                return userIdOf(subject);
            })
            .orElseGet(this::unauthenticatedOperator);
    }

    /** Resolves the caller when there is no workspace to check against yet (workspace creation). */
    public UUID resolveWithoutWorkspace(Jwt jwt) {
        if (jwt != null) {
            return currentUser.requireUserId(jwt);
        }
        return signedInSubject().map(this::userIdOf).orElseGet(this::unauthenticatedOperator);
    }

    /**
     * A person who signed in through the browser, if there is one.
     *
     * <p>Controllers ask for a {@code Jwt} because that is what a bearer-authenticated API call
     * carries. Somebody who logged in has an {@code OidcUser} instead and arrives here as
     * {@code null} — <b>which used to be indistinguishable from "nobody is authenticated" and fell
     * through to the platform operator</b>. Every signed-in person would then have acted as one
     * shared account: the wrong permissions, and an audit trail naming the wrong human.
     */
    private Optional<UUID> signedInSubject() {
        return currentUser.authenticatedSubject();
    }

    private UUID userIdOf(UUID subject) {
        return users.findBySubject(subject)
            .orElseThrow(() -> new NotInvitedException(
                "signed in, but this subject has no NAJEM account — invitations create accounts, "
                    + "and an account is never created by simply arriving with a valid token"));
    }

    private void requireRoleOf(UUID subject, UUID workspaceId, Role... allowed) {
        var role = access.roleIn(subject, workspaceId)
            .orElseThrow(() -> new AccessDeniedException("not a member of this workspace"));
        if (Arrays.stream(allowed).noneMatch(role::equals)) {
            throw new AccessDeniedException("role " + role + " may not perform this action");
        }
    }

    private UUID unauthenticatedOperator() {
        return operator
            .orElseThrow(() -> new AccessDeniedException(
                "no authenticated caller and no configured platform operator"))
            .userId();
    }
}
