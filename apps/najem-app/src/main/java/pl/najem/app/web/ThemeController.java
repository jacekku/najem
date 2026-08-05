package pl.najem.app.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Records a person's choice of light or dark, and returns them to the screen they were reading.
 *
 * <p><b>A POST, not a link</b>, for the same reason sign-out is one: this sets a cookie, and a GET
 * that changes state can be triggered by anything that fetches a URL. Thymeleaf puts the CSRF
 * token on the form, which is the protection the agency switcher already relies on.
 */
@Controller
public class ThemeController {

    /** A year. The choice is a preference, not a session — signing out must not undo it. */
    private static final int A_YEAR = 60 * 60 * 24 * 365;

    @PostMapping("/theme")
    public String choose(@RequestParam("theme") String theme,
                         @RequestParam(value = "return", required = false) String returnTo,
                         HttpServletResponse response) {
        // Only the two known values are written. Anything else and the cookie is cleared, which
        // returns the person to following their operating system — the state they were in before
        // they ever touched this control. There is no third theme to fall into.
        String chosen = ThemeAdvice.DARK.equals(theme) ? ThemeAdvice.DARK
            : ThemeAdvice.LIGHT.equals(theme) ? ThemeAdvice.LIGHT
                : null;

        Cookie cookie = new Cookie(ThemeAdvice.COOKIE, chosen == null ? "" : chosen);
        cookie.setPath("/");
        cookie.setMaxAge(chosen == null ? 0 : A_YEAR);
        // Not HttpOnly-sensitive and deliberately not Secure: this must keep working over plain
        // http on a developer's machine, and the value it carries is a colour scheme. It is
        // SameSite=Lax by container default, which is what stops another site from setting it.
        cookie.setHttpOnly(true);
        response.addCookie(cookie);

        return "redirect:" + safeReturnPath(returnTo);
    }

    /**
     * Only somewhere inside this application.
     *
     * <p>The return path arrives as a form field, so it is attacker-controllable, and it is used
     * as a redirect target — which is the exact shape of an open redirect. A path that does not
     * begin with a single {@code /} sends the person to another origin, and
     * {@code //evil.example} is protocol-relative: it looks like a path and is not one.
     * {@code /\} is the same trick, which some browsers normalise to {@code //}.
     *
     * <p>Rejecting falls back to the home page rather than erroring. A theme toggle is not a
     * place to show somebody a failure.
     */
    private static String safeReturnPath(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "/";
        }
        if (!candidate.startsWith("/")) {
            return "/";
        }
        if (candidate.startsWith("//") || candidate.startsWith("/\\")) {
            return "/";
        }
        return candidate;
    }
}
