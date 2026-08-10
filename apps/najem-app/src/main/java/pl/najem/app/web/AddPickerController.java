package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import pl.najem.reporting.application.PropertyBoardQuery;

import java.time.Clock;
import java.time.LocalDate;

/**
 * "Which property?" — the step between the shell's <em>+ Dodaj</em> chooser and a form that needs a
 * parent.
 *
 * <p>Creating a property is a route on its own ({@code /properties/new}); creating a lokal or a
 * najem is not, and cannot be. A unit is added to a property ({@code /properties/{id}/units/new})
 * and a tenancy is opened on a unit ({@code /units/{id}/reserve}), so a chooser row for either has
 * nowhere to point until a property is named. This screen is that naming, and it exists because the
 * alternative was worse in both directions: a chooser row that goes nowhere, or a global "add unit"
 * form whose first field is a property dropdown listing every property in the agency — which is the
 * same choice with less information, since a dropdown cannot show a manager which properties have
 * empty units.
 *
 * <p><b>Two intents, one list, and the difference is only where a row leads.</b> Lokal continues to
 * the property's add-unit form; Najem continues to its units, because a tenancy needs a unit chosen
 * and this application has no workspace-wide list of units to choose from — {@code UnitBoardQuery}
 * is per property, deliberately, and building a global one to serve a picker would be a read model
 * invented for a navigation step.
 *
 * <p>Reads {@link PropertyBoardQuery}, the same query {@code /properties} uses, so the occupancy
 * counts a manager needs in order to choose are already on the row: picking a property to put a new
 * unit in is a different question from picking one to open a tenancy in, and "how many are free"
 * answers the second one.
 */
@Controller
public class AddPickerController {

    /**
     * What the chosen property leads to. The template renders one heading and one row target per
     * value, so a third intent is a constant and a case rather than a second screen.
     */
    enum Intent {

        /** → {@code /properties/{id}/units/new}. */
        LOKAL,

        /** → {@code /properties/{id}/units}, where the unit to let is chosen. */
        NAJEM
    }

    private final PropertyBoardQuery properties;
    private final Clock clock;

    public AddPickerController(PropertyBoardQuery properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/dodaj/lokal")
    public String pickForUnit(WebWorkspace workspace, Model model) {
        return picker(Intent.LOKAL, workspace, model);
    }

    @GetMapping("/dodaj/najem")
    public String pickForTenancy(WebWorkspace workspace, Model model) {
        return picker(Intent.NAJEM, workspace, model);
    }

    private String picker(Intent intent, WebWorkspace workspace, Model model) {
        model.addAttribute("intent", intent.name());
        model.addAttribute("properties",
            properties.forWorkspace(workspace.workspaceId(), LocalDate.now(clock)));
        return "add-picker";
    }
}
