package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import pl.najem.reporting.application.PropertyBoardQuery;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The agency's portfolio — the entry point, and the only screen that yields the property ids every
 * other reporting call needs.
 *
 * <p>One query for the whole page. Asking Reporting for each property's occupancy in a loop is the
 * N+1 that {@code UnitBoardQuery} exists to avoid one level down, and a portfolio list is exactly
 * where it multiplies.
 */
@Controller
public class PropertiesScreenController {

    private final PropertyBoardQuery properties;
    private final Clock clock;

    public PropertiesScreenController(PropertyBoardQuery properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/properties")
    public String properties(WebWorkspace workspace, Model model) {
        model.addAttribute("properties",
            properties.forWorkspace(workspace.workspaceId(), LocalDate.now(clock)));
        return "properties";
    }
}
