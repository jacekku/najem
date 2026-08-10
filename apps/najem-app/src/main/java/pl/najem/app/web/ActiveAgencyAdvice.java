package pl.najem.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import pl.najem.um.domain.Role;

/**
 * Puts the name and role of the agency being acted in — and the path being rendered — on every
 * screen.
 *
 * <p>Only the home page said which agency you were in. Once a person can belong to several and
 * switch between them, a screen that does not say which one it is showing invites acting in the
 * wrong agency — and a misdirected write is sticky, because per-workspace uniqueness means it never
 * collides with the correct one. The switcher is what made this necessary.
 *
 * <p>The layout still resolves nothing: it is <em>handed</em> a name and a role, by the same
 * {@link WebWorkspaceResolver} every controller is handed a workspace by. One account of which
 * agency a request acts in, not two.
 *
 * <p>Absent rather than blank when there is no agency to name — an invitee who belongs to nothing,
 * or somebody at the chooser who has not answered yet. Neither is an error, and neither may be
 * turned into one by the sidebar: this advice runs before the handler, so an exception thrown here
 * would replace the page the person is meant to see.
 */
@ControllerAdvice(basePackageClasses = ActiveAgencyAdvice.class)
public class ActiveAgencyAdvice {

    private final WebWorkspaceResolver resolver;

    public ActiveAgencyAdvice(WebWorkspaceResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Who is signed in, for the sign-out control — and only when somebody actually is.
     *
     * <p>Under permit-all there is no session to end, so this is absent and the masthead renders no
     * button. A sign-out control in a deployment with no sign-in would be a control that cannot do
     * its job, which is worse than not offering it.
     *
     * <p>The preferred username, not the subject: a masthead saying which UUID you are is the same
     * defect as an agency screen showing its id instead of its name.
     */
    @ModelAttribute("signedInAs")
    public String signedInAs(@AuthenticationPrincipal OidcUser person) {
        if (person == null) {
            return null;
        }
        return person.getPreferredUsername() != null ? person.getPreferredUsername() : person.getSubject();
    }

    /**
     * The acting person's initials, for the sidebar footer's avatar.
     *
     * <p>That avatar was called as {@code avatar(initials=null)} — a hardcoded blank circle, on
     * every screen, for everybody, because no attribute carried initials for it to render. The
     * avatar fragment's own comment says a blank circle is legitimate and it still is: under
     * permit-all nobody is signed in, {@link #signedInAs} is {@code null}, and this returns
     * {@code null} so the circle stays blank. What changed is that a signed-in person now gets
     * their own letters instead of sharing the empty one.
     *
     * <p>The preferred username only, and deliberately NOT {@link #signedInAs}'s value. That method
     * falls back to the subject when Keycloak sends no preferred username, and a subject is a UUID:
     * abbreviating one produces a letter or two of an identifier wearing a person's initials. This
     * was written against {@code signedInAs} first and a test caught it — the subject
     * {@code abcdef01-2345-…} yielded "A", which is exactly the failure the paragraph below claimed
     * could not happen. So the rule is the narrower one: initials come from a name somebody chose,
     * or there are no initials.
     *
     * <p>The name is a username, so it is split on the separators usernames actually use —
     * {@code .}, {@code _}, {@code -} and whitespace — and the first letter of each of the first two
     * parts is taken. One part gives one letter rather than two letters of the same word, which
     * would read as a first and last name that do not exist.
     *
     * <p>{@code null} rather than a placeholder whenever there is nothing to abbreviate. A blank
     * circle is the honest rendering of "no name here", which is the same reason this advice returns
     * absent rather than blank everywhere else.
     */
    @ModelAttribute("signedInInitials")
    public String signedInInitials(@AuthenticationPrincipal OidcUser person) {
        if (person == null) {
            return null;
        }
        String name = person.getPreferredUsername();
        if (name == null) {
            return null;
        }
        StringBuilder initials = new StringBuilder();
        for (String part : name.split("[._\\-\\s]+")) {
            if (!part.isEmpty() && Character.isLetter(part.charAt(0))) {
                initials.append(Character.toUpperCase(part.charAt(0)));
                if (initials.length() == 2) {
                    break;
                }
            }
        }
        return initials.isEmpty() ? null : initials.toString();
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

    /**
     * The acting person's role in the active agency, as the Polish word the sidebar footer shows —
     * on every screen, the same reason {@link #activeAgency} moved here rather than staying a
     * home-page-only concern once the sidebar became permanent chrome.
     *
     * <p>The mapping used to live inline in {@code home.html}'s own {@code th:switch}; kept here
     * instead once a second template needed the same words, so the two cannot drift apart.
     * {@code home.html} now reads this attribute rather than repeating the switch.
     */
    @ModelAttribute("signedInRole")
    public String signedInRole(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        try {
            return displayName(resolver.resolve(jwt, request.getSession(false)).role());
        } catch (RuntimeException noAgencyToRoleIn) {
            return null;
        }
    }

    private static String displayName(Role role) {
        return switch (role) {
            case ADMIN -> "Administrator";
            case MANAGER -> "Zarządca";
            default -> role.name();
        };
    }

    /**
     * The path being rendered, for the sidebar to mark its own item active.
     *
     * <p>Read-only, and used for nothing but a class name on the matching nav item — never as a
     * redirect target. {@code ThemeAdvice} used to expose the same value for a different purpose
     * (a redirect back to the current page) and had to validate it as attacker-controllable on the
     * way back in; this one is never echoed into a header or a URL, so that treatment does not
     * apply here and must not be copied in if a redirect use ever reappears.
     *
     * <p>Thymeleaf 3.1 (Spring Boot 3.3) dropped {@code #httpServletRequest} from template
     * expressions, so the path has to arrive as a model attribute rather than be read in the
     * template itself.
     */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
