package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The scaffold the screens hang off. Deliberately holds no data: until the workspace seam exists
 * (plan task 2) there is nothing a page may show, because every fact in this system is scoped to a
 * workspace and a template must receive one rather than resolve it.
 *
 * <p>Lives in {@code pl.najem.app.web} — the composition root, not a module. The UI reads four
 * modules' read sides, which is the composition root's job and no module's. It calls application
 * services only: never a repository, never a {@code JdbcTemplate}, never another module's tables.
 */
@Controller
public class HomeController {

    /**
     * The Pulpit screen. The agency's name is real and everything else on it is not — see
     * {@link DashboardFake}, which is where the five read sides this screen wants will land.
     */
    @GetMapping("/")
    public String home(WebWorkspace workspace, Model model) {
        model.addAttribute("agency", workspace.name());
        model.addAttribute("dash", DashboardFake.VIEW);
        return "home";
    }
}
