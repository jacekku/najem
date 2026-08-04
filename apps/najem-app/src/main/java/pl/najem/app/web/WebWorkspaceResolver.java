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

    /**
     * The agencies the acting user belongs to, for the chooser.
     *
     * <p>Deliberately here rather than in the chooser controller: the controller cannot ask for a
     * {@link WebWorkspace}, because the case it exists to handle is precisely the one where no
     * workspace can be resolved. Resolving the subject a second time in the controller would be a
     * second implementation of "who is acting", and the two would drift.
     */
    public java.util.List<WorkspaceAccess.Membership> membershipsOf(Jwt jwt) {
        return access.forSubject(subjectOf(jwt));
    }

    public WebWorkspace resolve(Jwt jwt, HttpSession session) {
        UUID userId = caller.resolveWithoutWorkspace(jwt);
        UUID subject = subjectOf(jwt);

        var memberships = access.forSubject(subject);
        if (memberships.isEmpty()) {
            // A substantiated person who belongs to nothing yet — an invitee, most often. Still no
            // workspace, so still no data; but this is a state of the product rather than a
            // denial, and it used to throw AccessDeniedException, which told a new user they had
            // no access to an agency they had never asked for.
            throw new NoAgencyException("this user belongs to no agency yet");
        }

        var active = chosen(session)
            .map(chosenId -> memberships.stream()
                .filter(m -> m.workspaceId().equals(chosenId))
                .findFirst()
                // Re-checked on EVERY request, not only when it was chosen. A session value is
                // client-influenced input, not a credential, and membership can be revoked between
                // one request and the next.
                .orElseThrow(() -> new AccessDeniedException("not a member of the chosen workspace")))
            .orElseGet(() -> {
                // Exactly one membership is not a choice, so acting in it is unambiguous.
                if (memberships.size() == 1) {
                    return memberships.getFirst();
                }
                // Several, and nobody has said which. Picking one would be a default deciding
                // WHOSE data a request acts on — roadmap rule 7 forbids exactly that, and a
                // misdirected write is sticky because uniqueness is per workspace. Still a
                // refusal to proceed; but being ASKED to choose is not being denied, so this
                // sends the person to the chooser rather than to "Brak dostępu".
                throw new ChoiceRequiredException(
                    "this user belongs to several agencies and none has been chosen");
            });

        return new WebWorkspace(active.workspaceId(), active.name(), userId, subject, active.role());
    }

    /**
     * Remembers the chosen agency, having checked the acting user is a member of it.
     *
     * <p>The check here is not what makes the session value safe — {@link #resolve} re-checks it on
     * every request, because membership can be revoked between one request and the next. This one
     * exists so that choosing an agency you do not belong to is refused at the moment you do it,
     * rather than accepted and then silently failing on the next page.
     */
    public void choose(Jwt jwt, HttpSession session, UUID workspaceId) {
        if (!access.canAccess(subjectOf(jwt), workspaceId)) {
            throw new AccessDeniedException("not a member of the chosen agency");
        }
        session.setAttribute(SESSION_KEY, workspaceId);
    }

    private UUID subjectOf(Jwt jwt) {
        return jwt != null
            ? currentUser.subject(jwt)
            : access.subjectOf(caller.resolveWithoutWorkspace(jwt)).orElseThrow(
                () -> new AccessDeniedException("no Keycloak subject for the acting user"));
    }

    private java.util.Optional<UUID> chosen(HttpSession session) {
        if (session == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable((UUID) session.getAttribute(SESSION_KEY));
    }
}
