package pl.najem.app.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import pl.najem.um.adapter.security.CurrentUser;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.application.WorkspaceCaller;

import java.util.UUID;

/**
 * Checks that a caller naming a workspace in {@code X-Workspace-Id} is actually a member of it.
 *
 * <p>Every module's REST controller takes the workspace from that header and compares it with
 * nothing. They cannot do better on their own: <b>no module depends on usermanagement</b>, so
 * {@link WorkspaceAccess} is unreachable from accounting, contacts, reporting and property
 * management by construction rather than by oversight (najem-reviewer, najem-build seq 194 §3).
 * The composition root is the only place with access to both the caller and the modules, so the
 * check lives here — one file, no module changes, no new contract surface.
 *
 * <p><b>Not a substitute for the modules' own scoping.</b> This says the caller belongs to the
 * workspace they named; it does not say the aggregate they then touch belongs to that workspace.
 * PM's endpoints, which never name a workspace at all, are untouched by this and remain theirs.
 */
@Component
public class WorkspaceHeaderInterceptor implements HandlerInterceptor {

    static final String HEADER = "X-Workspace-Id";

    private final WorkspaceCaller caller;
    private final WorkspaceAccess access;
    private final CurrentUser currentUser;

    public WorkspaceHeaderInterceptor(WorkspaceCaller caller, WorkspaceAccess access,
                                      CurrentUser currentUser) {
        this.caller = caller;
        this.access = access;
        this.currentUser = currentUser;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String named = request.getHeader(HEADER);
        if (named == null || named.isBlank()) {
            // No workspace named, nothing to check against. The modules' own DEV fallback decides
            // what happens next, and that fallback is a separately reported problem — closing it
            // here would hide it rather than fix it.
            return true;
        }

        UUID workspaceId;
        try {
            workspaceId = UUID.fromString(named.trim());
        } catch (IllegalArgumentException malformed) {
            // Unparseable is not "no workspace": it is a caller asserting something this
            // application cannot evaluate, and uncertainty resolves to denied (rule 7).
            return deny(response);
        }

        UUID subject;
        Jwt jwt = jwt();
        if (jwt != null) {
            // Resolved OUTSIDE the catch, deliberately. CurrentUser.subject throws the same
            // AccessDeniedException when a token's `sub` is not a UUID, so catching around this
            // let a token that passed signature, expiry, issuer and audience wave the header
            // through unchecked — in the one deployment mode this interceptor exists to protect.
            // A present-but-unusable token is a denial, not an absence of authentication.
            // najem-reviewer, najem-build seq 299 and 320.
            subject = currentUser.subject(jwt);
        } else {
            try {
                subject = unauthenticatedSubject();
            } catch (AccessDeniedException noOperator) {
                // No token AND no configured operator. Reachable only where najem.security.permit-all
                // was set explicitly, which cannot be acquired by omission — so the permissive path
                // is one somebody chose, not one this code chose for them.
                return true;
            }
        }

        return access.canAccess(subject, workspaceId) || deny(response);
    }

    private UUID unauthenticatedSubject() {
        UUID userId = caller.resolveWithoutWorkspace(null);
        return access.subjectOf(userId)
            .orElseThrow(() -> new AccessDeniedException("no Keycloak subject for the acting user"));
    }

    /**
     * 404, never 403. A 403 confirms the workspace exists, which turns this header into an
     * existence oracle for other agencies' ids — and "not yours" and "not there" must be
     * indistinguishable from outside.
     */
    private static boolean deny(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return false;
    }

    private static Jwt jwt() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token ? token.getToken() : null;
    }
}
