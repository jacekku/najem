package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import pl.najem.reporting.application.UnitBoardQuery;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The units of one property.
 *
 * <p>Reads Reporting's projection rather than Property Management's tables: Reporting is the
 * sanctioned read side over PM's granted streams, and it already carries domain nuance a template
 * would otherwise have to reinvent — notably the three market states, where a unit nobody has ever
 * listed (<em>inventory</em>) is not the same as one deliberately closed for renovation.
 *
 * <p>Renders what it is given and computes nothing. Occupancy comes from the projection; whether a
 * unit is available is the projection's answer, not this class's.
 */
@Controller
public class UnitsScreenController {

    private final UnitBoardQuery units;
    private final Clock clock;

    public UnitsScreenController(UnitBoardQuery units, Clock clock) {
        this.units = units;
        this.clock = clock;
    }

    // TODO: this does not check that the caller owns the property, and now that matters more than
    // it did. Nothing leaks — UnitBoardQuery is workspace-scoped, so another agency's id renders an
    // empty board rather than their flats — but the board now carries a "Dodaj lokal" button, so it
    // offers an action on a building the caller cannot see, and the add-unit GET then 404s on it
    // (AddUnitScreenController calls requireOwnsProperty, deliberately, so the form never opens for
    // a foreign property). The honest answer is for this screen to 404 too, the same
    // undifferentiated answer unknown and foreign get everywhere else. Left out of the add-property
    // work because it changes an existing screen's behaviour for ids that today render an empty
    // page, and that is a decision about this screen rather than about the new ones.
    @GetMapping("/properties/{propertyId}/units")
    public String unitsOf(@PathVariable UUID propertyId, WebWorkspace workspace, Model model) {
        model.addAttribute("propertyId", propertyId);
        model.addAttribute("units",
            units.forProperty(workspace.workspaceId(), propertyId, LocalDate.now(clock)));
        return "units";
    }
}
