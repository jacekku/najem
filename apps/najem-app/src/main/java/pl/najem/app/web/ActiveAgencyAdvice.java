package pl.najem.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the name of the agency being acted in on every screen.
 *
 * <p>Only the home page said which agency you were in. Once a person can belong to several and
 * switch between them, a screen that does not say which one it is showing invites acting in the
 * wrong agency — and a misdirected write is sticky, because per-workspace uniqueness means it never
 * collides with the correct one. The switcher is what made this necessary.
 *
 * <p>The layout still resolves nothing: it is <em>handed</em> a name, by the same
 * {@link WebWorkspaceResolver} every controller is handed a workspace by. One account of which
 * agency a request acts in, not two.
 *
 * <p>Absent rather than blank when there is no agency to name — an invitee who belongs to nothing,
 * or somebody at the chooser who has not answered yet. Neither is an error, and neither may be
 * turned into one by a masthead: this advice runs before the handler, so an exception thrown here
 * would replace the page the person is meant to see.
 */
@ControllerAdvice(basePackageClasses = ActiveAgencyAdvice.class)
public class ActiveAgencyAdvice {

    private final WebWorkspaceResolver resolver;

    public ActiveAgencyAdvice(WebWorkspaceResolver resolver) {
        this.resolver = resolver;
    }

    @ModelAttribute("activeAgency")
    public String activeAgency(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        try {
            return resolver.resolve(jwt, request.getSession(false)).name();
        } catch (RuntimeException noAgencyToName) {
            // Deliberately broad, and deliberately silent. Every reason resolution can fail —
            // belongs to nothing, has not chosen, chose one they have since been removed from —
            // is already answered properly by the handler about to run. Re-raising here would
            // replace that answer with this one.
            return null;
        }
    }
}
