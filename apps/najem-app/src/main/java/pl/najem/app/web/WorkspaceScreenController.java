package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The first workspace-scoped screen: it shows which workspace the request resolved to and what the
 * acting user may do in it.
 *
 * <p>Thin on purpose. Its job is to prove the seam end to end — that a screen is handed a checked
 * workspace rather than asking for one — and to be the shell the switcher (plan task 4b) extends.
 */
@Controller
public class WorkspaceScreenController {

    @GetMapping("/workspace")
    public String show(WebWorkspace workspace, Model model) {
        model.addAttribute("workspaceId", workspace.workspaceId());
        model.addAttribute("role", workspace.role().name());
        return "workspace";
    }
}
