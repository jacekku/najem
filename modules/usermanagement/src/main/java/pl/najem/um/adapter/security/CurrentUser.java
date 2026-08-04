package pl.najem.um.adapter.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.domain.Role;

import java.util.Arrays;
import java.util.UUID;

/**
 * Turns a JWT into NAJEM's own notion of who is calling. The token is trusted for exactly one claim
 * — {@code sub} — and never for roles or workspace membership; those resolve from this module's own
 * projection (decision D1, Keycloak is the identity provider only).
 */
@Component
public class CurrentUser {

    private final UserService users;
    private final WorkspaceAccess access;

    public CurrentUser(UserService users, WorkspaceAccess access) {
        this.users = users;
        this.access = access;
    }

    public UUID subject(Jwt jwt) {
        if (jwt == null) {
            throw new AccessDeniedException("no authenticated caller");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AccessDeniedException("token subject is not a Keycloak user id");
        }
    }

    public UUID requireUserId(Jwt jwt) {
        return users.findBySubject(subject(jwt))
            .orElseThrow(() -> new AccessDeniedException("no NAJEM user for this subject"));
    }

    public void requireRole(Jwt jwt, UUID workspaceId, Role... allowed) {
        var role = access.roleIn(subject(jwt), workspaceId)
            .orElseThrow(() -> new AccessDeniedException("not a member of this workspace"));
        if (Arrays.stream(allowed).noneMatch(role::equals)) {
            throw new AccessDeniedException("role " + role + " may not perform this action");
        }
    }
}
