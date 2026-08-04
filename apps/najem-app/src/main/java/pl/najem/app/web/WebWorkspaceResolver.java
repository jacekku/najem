package pl.najem.app.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import pl.najem.um.adapter.security.CurrentUser;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.application.WorkspaceCaller;

import java.util.UUID;

/**
 * Resolves the workspace a request acts in, from UserManagement's own projection.
 *
 * <p>Nothing here invents a notion of identity, membership or role: the acting user comes from
 * {@link WorkspaceCaller} (a JWT when secured, the configured platform operator when not, and
 * {@link AccessDeniedException} when neither — an unauthenticated request never ends up acting as
 * somebody), and the memberships come from {@link WorkspaceAccess}. Keycloak is trusted for
 * {@code sub} and nothing else.
 */
@Component
public class WebWorkspaceResolver {

    /** Where a chosen workspace is remembered between requests. Chosen, never trusted. */
    static final String SESSION_KEY = "najem.activeWorkspace";

    private final WorkspaceCaller caller;
    private final WorkspaceAccess access;
    private final CurrentUser currentUser;

    public WebWorkspaceResolver(WorkspaceCaller caller, WorkspaceAccess access, CurrentUser currentUser) {
        this.caller = caller;
        this.access = access;
        this.currentUser = currentUser;
    }

    public WebWorkspace resolve(Jwt jwt, HttpSession session) {
        UUID userId = caller.resolveWithoutWorkspace(jwt);
        UUID subject = jwt != null
            ? currentUser.subject(jwt)
            : access.subjectOf(userId).orElseThrow(
                () -> new AccessDeniedException("no Keycloak subject for the acting user"));

        var memberships = access.forSubject(subject);
        if (memberships.isEmpty()) {
            // Not a state to render around: matches WorkspaceCaller's rule that a request must
            // never end up acting as somebody it cannot substantiate.
            throw new AccessDeniedException("this user belongs to no workspace");
        }

        var active = chosen(session)
            .map(chosenId -> memberships.stream()
                .filter(m -> m.workspaceId().equals(chosenId))
                .findFirst()
                // Re-checked on EVERY request, not only when it was chosen. A session value is
                // client-influenced input, not a credential, and membership can be revoked between
                // one request and the next.
                .orElseThrow(() -> new AccessDeniedException("not a member of the chosen workspace")))
            // forSubject orders by joined_on, so "the first" is the earliest joined rather than
            // whichever row the database happened to return. Deterministic until 4b's switcher.
            .orElseGet(() -> memberships.getFirst());

        return new WebWorkspace(active.workspaceId(), userId, subject, active.role());
    }

    private java.util.Optional<UUID> chosen(HttpSession session) {
        if (session == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable((UUID) session.getAttribute(SESSION_KEY));
    }
}
