package pl.najem.um.application;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import pl.najem.um.adapter.security.CurrentUser;
import pl.najem.um.domain.Role;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the acting user for a request.
 *
 * <p>With security enabled there is always a JWT and this is a thin wrapper over {@link CurrentUser}.
 * With security disabled — the default for local runs and the walking skeleton — there is no token,
 * and the only acceptable caller is the explicitly configured platform operator. If neither exists
 * the request is denied rather than silently allowed: an unauthenticated request must never end up
 * acting as somebody.
 */
@Component
public class WorkspaceCaller {

    private final CurrentUser currentUser;
    private final Optional<PlatformOperator> operator;

    public WorkspaceCaller(CurrentUser currentUser, Optional<PlatformOperator> operator) {
        this.currentUser = currentUser;
        this.operator = operator;
    }

    /** Resolves the caller and enforces that they hold one of {@code allowed} in the workspace. */
    public UUID resolve(Jwt jwt, UUID workspaceId, Role... allowed) {
        if (jwt != null) {
            currentUser.requireRole(jwt, workspaceId, allowed);
            return currentUser.requireUserId(jwt);
        }
        return unauthenticatedOperator();
    }

    /** Resolves the caller when there is no workspace to check against yet (workspace creation). */
    public UUID resolveWithoutWorkspace(Jwt jwt) {
        return jwt != null ? currentUser.requireUserId(jwt) : unauthenticatedOperator();
    }

    private UUID unauthenticatedOperator() {
        return operator
            .orElseThrow(() -> new AccessDeniedException(
                "no authenticated caller and no configured platform operator"))
            .userId();
    }
}
