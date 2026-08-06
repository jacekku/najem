package pl.najem.pm.application;

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

/**
 * Properties and the units inside them.
 *
 * <p>Every command takes the workspace of the caller making it, and checks it against the aggregate
 * it is about to change. That used to be split in two: the controller asked {@code WorkspaceGuard}
 * whether a row with this id existed in the caller's workspace, and then the service rebuilt the
 * aggregate from its stream to find out the same workspace again. Two answers to one question, from
 * two stores that a second statement is allowed to leave disagreeing — and a wasted rebuild per
 * write.
 *
 * <p>Its store is reached through {@link PortfolioProjection}, which is write-only on purpose:
 * pm_property and pm_unit are derived from these same streams, so nothing here may read a row back
 * to decide something.
 *
 * <p>Now the rebuild does both jobs. {@code addUnit} loads the parent property once, which both
 * refuses a caller who does not own it and supplies the workspace the new unit is stamped with. The
 * child still never takes a caller-supplied workspaceId for its own row: the caller says who they
 * are, and the parent says what the child inherits.
 */
@Service
@Transactional
public class PortfolioService {

    private final EventStore store;
    private final PortfolioProjection projection;

    public PortfolioService(EventStore store, PortfolioProjection projection) {
        this.store = store;
        this.projection = projection;
    }

    /**
     * The one command with nothing to check against: a property that did not exist a moment ago has
     * no prior owner, so the caller's workspace is stamped on it rather than compared to it.
     */
    public UUID createProperty(UUID workspaceId, String address, List<Owner> owners) {
        UUID propertyId = UUID.randomUUID();
        store.append(propertyId, "Property", 0,
            Property.create(propertyId, workspaceId, address, owners), List.of());
        projection.propertyCreated(propertyId, workspaceId, address);
        return propertyId;
    }

    public UUID addUnit(UUID workspaceId, UUID propertyId, String name, BigDecimal baseRent) {
        propertyOwnedBy(workspaceId, propertyId);
        UUID unitId = UUID.randomUUID();
        store.append(unitId, "Unit", 0,
            Unit.add(unitId, workspaceId, propertyId, name, baseRent), List.of());
        projection.unitAdded(unitId, propertyId, workspaceId, name, baseRent,
            Unit.MarketState.INVENTORY);
        return unitId;
    }

    public void setUnitBaseRent(UUID workspaceId, UUID unitId, BigDecimal amount) {
        var stream = store.load(unitId, "Unit");
        var unit = Unit.from(stream.events());
        unit.requireOwnedBy(workspaceId);
        store.append(unitId, "Unit", stream.version(), unit.setBaseRent(amount), List.of());
        projection.baseRentSet(unitId, unit.workspaceId(), amount);
    }

    public void updateUnitDetails(UUID workspaceId, UUID unitId, Map<String, String> details) {
        var stream = store.load(unitId, "Unit");
        var unit = Unit.from(stream.events());
        unit.requireOwnedBy(workspaceId);
        store.append(unitId, "Unit", stream.version(), unit.updateDetails(details), List.of());
        if (details.containsKey("listingRef")) {
            projection.listingRefSet(unitId, unit.workspaceId(), details.get("listingRef"));
        }
    }

    public void openUnitToRent(UUID workspaceId, UUID unitId, String reason) {
        applyMarketTransition(workspaceId, unitId, reason, true);
    }

    public void closeUnitToRent(UUID workspaceId, UUID unitId, String reason) {
        applyMarketTransition(workspaceId, unitId, reason, false);
    }

    /**
     * Sold, demolished, or entered by mistake. Nothing is blocked and nothing is warned: the one
     * hard invariant is period overlap, and a removed unit keeps its calendar, so removing a flat
     * that still has a sitting tenant is a transaction the manager may legitimately be recording.
     */
    public void removeUnit(UUID workspaceId, UUID unitId, String reason) {
        var stream = store.load(unitId, "Unit");
        var unit = Unit.from(stream.events());
        unit.requireOwnedBy(workspaceId);
        store.append(unitId, "Unit", stream.version(), unit.remove(reason), List.of());
        projection.marketStateSet(unitId, unit.workspaceId(), Unit.MarketState.REMOVED);
    }

    private void applyMarketTransition(UUID workspaceId, UUID unitId, String reason, boolean open) {
        var stream = store.load(unitId, "Unit");
        var unit = Unit.from(stream.events());
        unit.requireOwnedBy(workspaceId);
        var events = open ? unit.openToRent(reason) : unit.closeToRent(reason);
        store.append(unitId, "Unit", stream.version(), events, List.of());
        projection.marketStateSet(unitId, unit.workspaceId(),
            open ? Unit.MarketState.OPEN : Unit.MarketState.CLOSED);
    }

    private Property propertyOwnedBy(UUID workspaceId, UUID propertyId) {
        var property = Property.from(store.load(propertyId, "Property").events());
        property.requireOwnedBy(workspaceId);
        return property;
    }

    /**
     * Refuses a caller who does not own this property. For another service that needs the portfolio
     * to vouch for an asset before hanging something off it — {@link RepairService} is the caller.
     *
     * <p>These replace {@code workspaceOf} and {@code workspaceOfUnit}, which answered "whose is
     * this?" and left the comparing to the caller. Nobody wanted the answer: every caller had a
     * workspace already and only needed to know whether it matched. Handing back an owner meant the
     * check could be forgotten, and it was a second read of a fact the controller's guard had just
     * looked up in the projection tables.
     *
     * <p>Two methods rather than one taking a {@code RepairScope}: the portfolio has no business
     * knowing that repairs classify their assets, and the day a second caller needs this it will not
     * be talking about repairs either. Mapping a scope onto a property or a unit belongs to whoever
     * has the scope.
     */
    public void requireOwnsProperty(UUID workspaceId, UUID propertyId) {
        propertyOwnedBy(workspaceId, propertyId);
    }

    public void requireOwnsUnit(UUID workspaceId, UUID unitId) {
        Unit.from(store.load(unitId, "Unit").events()).requireOwnedBy(workspaceId);
    }
}
