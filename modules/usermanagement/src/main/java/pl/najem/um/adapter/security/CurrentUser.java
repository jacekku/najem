package pl.najem.um.adapter.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import pl.najem.um.application.AuthenticatedSubject;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.domain.Role;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a JWT into NAJEM's own notion of who is calling. The token is trusted for exactly one claim
 * — {@code sub} — and never for roles or workspace membership; those resolve from this module's own
 * projection (decision D1, Keycloak is the identity provider only).
 *
 * <p>Implements {@link AuthenticatedSubject}, which is how the application layer asks this question
 * without importing Spring Security or this package. Everything here that takes a {@link Jwt} is for
 * the web layer, which legitimately holds one; the port carries only a subject.
 */
@Component
public class CurrentUser implements AuthenticatedSubject {

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
        return asSubject(jwt.getSubject());
    }

    /**
     * The caller's subject, whichever way they authenticated.
     *
     * <p>A browser signs in through the authorization-code flow and its principal is an
     * {@link OidcUser}; a machine client presents a bearer token and its principal is a {@link Jwt}.
     * Controllers ask for a {@code Jwt}, so a signed-in person arrives as {@code null} there — and
     * the fallback for "no token" is the platform operator. <b>Without this, every person who logged
     * in would act as the operator</b>, which is one account doing everything and an audit trail
     * naming the wrong human.
     *
     * <p>Empty means nobody is authenticated, which is a different thing from being refused: under
     * permit-all there is genuinely no caller, and the operator fallback is correct there.
     */
    @Override
    public Optional<UUID> current() {
        return authenticatedSubject();
    }

    public Optional<UUID> authenticatedSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcUser oidc) {
            return Optional.of(asSubject(oidc.getSubject()));
        }
        if (principal instanceof Jwt jwt) {
            return Optional.of(asSubject(jwt.getSubject()));
        }
        // An anonymous authentication, or a principal shape nobody here understands. Both are
        // "we cannot say who this is", and a caller we cannot name is not a caller we trust.
        return Optional.empty();
    }

    private static UUID asSubject(String claim) {
        try {
            return UUID.fromString(claim);
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
