package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The sign-in page.
 *
 * <p>It holds a link, not a form. Keycloak is the identity provider (human ruling) and the page
 * that asks for a password is Keycloak's own — <b>nothing in NAJEM receives, stores, hashes or
 * compares one</b>, and there is no route here that could begin to. What this page exists for is
 * to say where somebody is about to be sent, in Polish, before they are sent there.
 *
 * <p>Registered only when an identity provider is configured. Under permit-all there is nothing to
 * sign in to, and a sign-in page that cannot sign anybody in is a button that reports a broken
 * deployment as a broken login.
 */
@Controller
@org.springframework.context.annotation.Conditional(pl.najem.um.adapter.security.IssuerConfigured.class)
public class LoginController {

    /**
     * Spring redirects here with {@code ?error} when the authorization code exchange fails, and the
     * logout handler with {@code ?wylogowano}. Both are states a person can reach without anything
     * being wrong, so both are told plainly rather than left as a bare form.
     */
    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String wylogowano,
                        Model model) {
        model.addAttribute("failed", error != null);
        model.addAttribute("signedOut", wylogowano != null);
        return "login";
    }
}
