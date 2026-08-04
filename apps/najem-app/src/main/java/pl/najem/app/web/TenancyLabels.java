package pl.najem.app.web;

import org.springframework.stereotype.Component;
import pl.najem.reporting.application.PropertyBoardQuery;
import pl.najem.reporting.application.UnitBoardQuery;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a tenancy id into something a person can recognise: "ul. Marszałkowska 12 · m. 1".
 *
 * <p>The arrears board is keyed by tenancy and carries nothing else — colour and id. Rendered as
 * given, it is a column of UUIDs, which tells a manager which of their tenancies is in arrears in
 * the least usable way available. Naming the unit is the difference between a board somebody can
 * act on and one they have to look things up from.
 *
 * <p><b>This is composition, not recomputation.</b> Accounting decides the colour; Reporting knows
 * which unit a tenancy occupies and which property that unit is in. Joining two modules' read sides
 * is the composition root's job and no module's — it is the reason the UI lives here (plan decision
 * A). Nothing about arrears is derived, and a tenancy with no label still renders with its colour.
 *
 * <p><b>Cost, stated rather than hidden: one query per property.</b> Bounded by the portfolio, not
 * by the size of the board, so it does not grow with arrears — but it is a fan-out and I would
 * rather it were not here. <b>The durable fix is Accounting putting the unit on the board row</b>,
 * which is what I asked for at najem-build seq 21 and did not get; until then this is the honest
 * version, because the alternative is a screen full of identifiers.
 */
@Component
public class TenancyLabels {

    private final PropertyBoardQuery properties;
    private final UnitBoardQuery units;

    public TenancyLabels(PropertyBoardQuery properties, UnitBoardQuery units) {
        this.properties = properties;
        this.units = units;
    }

    /**
     * Labels for every tenancy currently occupying a unit. A tenancy that has ended, or one that
     * has not started, is absent — deliberately: inventing a label for a tenancy the occupancy
     * projection does not place would be this class guessing where the board is silent.
     */
    public Map<UUID, String> forWorkspace(UUID workspaceId, LocalDate asOf) {
        var labels = new HashMap<UUID, String>();
        for (var property : properties.forWorkspace(workspaceId, asOf)) {
            for (var unit : units.forProperty(workspaceId, property.propertyId(), asOf)) {
                if (unit.currentTenancyId() != null) {
                    labels.put(unit.currentTenancyId(), property.address() + " · " + unit.name());
                }
            }
        }
        return labels;
    }
}
