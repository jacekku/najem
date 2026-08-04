package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The agency screen under its older route.
 *
 * <p>It used to render a second, near-identical template of its own. It renders {@code home} now:
 * the human ruled that {@code /} routes straight to the agency, which made the two screens the same
 * screen, and two templates showing the same facts drift apart rather than stay in step.
 *
 * <p>Kept as a route rather than deleted because the switcher (plan task 4b) extends it, and
 * because the seam tests address it.
 */
@Controller
public class WorkspaceScreenController {

    @GetMapping("/workspace")
    public String show(WebWorkspace workspace, Model model) {
        model.addAttribute("workspaceId", workspace.workspaceId());
        model.addAttribute("role", workspace.role().name());
        return "home";
    }
}
