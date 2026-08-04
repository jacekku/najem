package pl.najem.app.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.UUID;

/**
 * Choosing which agency to act in.
 *
 * <p>The one screen that cannot receive a {@link WebWorkspace}: it exists precisely for the case
 * where none can be resolved, because the person belongs to several and has named none. It asks
 * {@link WebWorkspaceResolver} for the memberships instead — the same resolver, so there is one
 * account of who is acting rather than two that can drift.
 *
 * <p>Choosing is a POST. A GET that changes which agency every later request acts in would be a
 * state change reachable by a link, a prefetch, or a browser restoring tabs.
 */
@Controller
public class AgencyChooserController {

    private final WebWorkspaceResolver resolver;

    public AgencyChooserController(WebWorkspaceResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/agencies")
    public String choices(@AuthenticationPrincipal Jwt jwt, Model model) {
        model.addAttribute("memberships", resolver.membershipsOf(jwt));
        return "agencies";
    }

    @PostMapping("/agencies/{workspaceId}")
    public String choose(@PathVariable UUID workspaceId,
                         @AuthenticationPrincipal Jwt jwt,
                         HttpSession session) {
        resolver.choose(jwt, session, workspaceId);
        return "redirect:/";
    }
}
