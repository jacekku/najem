package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.Property;
import pl.najem.pm.domain.Unit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class PortfolioService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public PortfolioService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID createProperty(UUID workspaceId, String address, List<Owner> owners) {
        UUID propertyId = UUID.randomUUID();
        store.append(propertyId, "Property", 0,
            Property.create(propertyId, workspaceId, address, owners), List.of());
        jdbc.update("insert into pm_property(property_id, workspace_id, address) values (?,?,?)",
            propertyId, workspaceId, address);
        return propertyId;
    }

    public UUID addUnit(UUID propertyId, String name, BigDecimal baseRent) {
        UUID workspaceId = workspaceOf(propertyId);
        UUID unitId = UUID.randomUUID();
        store.append(unitId, "Unit", 0,
            Unit.add(unitId, workspaceId, propertyId, name, baseRent), List.of());
        jdbc.update("insert into pm_unit(unit_id, property_id, workspace_id, name, base_rent, market_state) "
            + "values (?,?,?,?,?,?)", unitId, propertyId, workspaceId, name, baseRent,
            Unit.MarketState.INVENTORY.name());
        return unitId;
    }

    public void setUnitBaseRent(UUID unitId, BigDecimal amount) {
        var stream = store.load(unitId, "Unit");
        store.append(unitId, "Unit", stream.version(),
            Unit.from(stream.events()).setBaseRent(amount), List.of());
        jdbc.update("update pm_unit set base_rent = ? where unit_id = ?", amount, unitId);
    }

    public void updateUnitDetails(UUID unitId, Map<String, String> details) {
        var stream = store.load(unitId, "Unit");
        var unit = Unit.from(stream.events());
        store.append(unitId, "Unit", stream.version(), unit.updateDetails(details), List.of());
        if (details.containsKey("listingRef")) {
            jdbc.update("update pm_unit set listing_ref = ? where unit_id = ?",
                details.get("listingRef"), unitId);
        }
    }

    public void openUnitToRent(UUID unitId, String reason) {
        applyMarketTransition(unitId, reason, true);
    }

    public void closeUnitToRent(UUID unitId, String reason) {
        applyMarketTransition(unitId, reason, false);
    }

    /**
     * Sold, demolished, or entered by mistake. Nothing is blocked and nothing is warned: the one
     * hard invariant is period overlap, and a removed unit keeps its calendar, so removing a flat
     * that still has a sitting tenant is a transaction the manager may legitimately be recording.
     */
    public void removeUnit(UUID unitId, String reason) {
        var stream = store.load(unitId, "Unit");
        store.append(unitId, "Unit", stream.version(),
            Unit.from(stream.events()).remove(reason), List.of());
        jdbc.update("update pm_unit set market_state = ? where unit_id = ?",
            Unit.MarketState.REMOVED.name(), unitId);
    }

    private void applyMarketTransition(UUID unitId, String reason, boolean open) {
        var stream = store.load(unitId, "Unit");
        var unit = Unit.from(stream.events());
        var events = open ? unit.openToRent(reason) : unit.closeToRent(reason);
        store.append(unitId, "Unit", stream.version(), events, List.of());
        jdbc.update("update pm_unit set market_state = ? where unit_id = ?",
            (open ? Unit.MarketState.OPEN : Unit.MarketState.CLOSED).name(), unitId);
    }

    /**
     * The workspace of a property, read from its own stream. Children never take a
     * caller-supplied workspaceId — that makes cross-workspace writes structurally impossible
     * rather than merely validated.
     */
    public UUID workspaceOf(UUID propertyId) {
        return Property.from(store.load(propertyId, "Property").events()).workspaceId();
    }
}
