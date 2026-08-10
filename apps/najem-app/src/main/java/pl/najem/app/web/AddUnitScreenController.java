package pl.najem.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pl.najem.pm.application.PortfolioService;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Adding a flat to a building.
 *
 * <p>The GET checks ownership before it renders. {@code addUnit} checks it again on the way in, so
 * this is not the security boundary — it is the difference between a form that will work and a form
 * that looks fine and fails on submit. Rendering a write form for a building the caller cannot
 * write to is a screen that lies about what is going to happen.
 *
 * <p>Two submit buttons, distinguished by {@code action}: a twenty-flat building is entered in one
 * sitting, and going back to the board between each one is twenty round trips to look at a list
 * nobody is reading yet.
 */
@Controller
public class AddUnitScreenController {

    private final PortfolioService portfolio;

    public AddUnitScreenController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping("/properties/{propertyId}/units/new")
    public String form(@PathVariable UUID propertyId,
                       @RequestParam(name = "added", required = false) String added,
                       WebWorkspace workspace, Model model) {
        portfolio.requireOwnsProperty(workspace.workspaceId(), propertyId);
        model.addAttribute("propertyId", propertyId);
        model.addAttribute("added", added);
        return "unit-new";
    }

    @PostMapping("/properties/{propertyId}/units")
    public String create(@PathVariable UUID propertyId,
                         @RequestParam String name,
                         @RequestParam BigDecimal baseRent,
                         @RequestParam(defaultValue = "save") String action,
                         WebWorkspace workspace, RedirectAttributes flash) {
        UUID workspaceId = workspace.workspaceId();
        try {
            portfolio.addUnit(workspaceId, propertyId, name, baseRent);
        } catch (IllegalArgumentException blankNameOrNegativeRent) {
            // Unit.add's own refusal — a blank name or a null/negative base rent. Caught here
            // rather than re-checked, so the rule stays in one place; converted here rather than
            // in WebErrorAdvice because a bare IllegalArgumentException is deliberately NOT mapped
            // there (see UnitScreenController.chosen) — catching it globally would also catch
            // every unrelated bad-argument bug in the package and turn it into a quiet 400.
            //
            // This call also rehydrates the parent Property, and that used to be inside the same
            // net: Property.apply's unknown-event branch threw IllegalArgumentException, so a
            // deployment missing an event registration told the manager their request was bad and
            // named a Java class. It now throws UnknownEventException, which nothing maps — an
            // application bug answers 500. Narrowed at the source rather than here, because the
            // same catch exists on the property screen and would have needed the same memory.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, blankNameOrNegativeRent.getMessage());
        }

        if ("another".equals(action)) {
            // The name rides in the query string rather than a flash attribute because the manager
            // may reload this form, and a flash survives exactly one render — the confirmation
            // would vanish on refresh and read as though the unit had not been saved.
            return "redirect:/properties/" + propertyId + "/units/new?added="
                + java.net.URLEncoder.encode(name.strip(), java.nio.charset.StandardCharsets.UTF_8);
        }
        flash.addFlashAttribute("added", name.strip());
        return "redirect:/properties/" + propertyId + "/units";
    }
}
