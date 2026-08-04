package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A rentable space: market state plus (Task 3) the schedule of tenancy periods.
 *
 * <p>Open/close are free transitions with a reason — the domain deliberately has no
 * market-state wall. The only hard rule on a Unit is period overlap (Task 3).
 */
public class Unit {

    public enum MarketState { INVENTORY, OPEN, CLOSED, REMOVED }

    private UUID id;
    private UUID workspaceId;
    private UUID propertyId;
    private String name;
    private BigDecimal baseRent;
    private MarketState marketState;
    private String listingRef;

    Unit() {
    }

    public static List<Object> add(UUID unitId, UUID workspaceId, UUID propertyId,
                                   String name, BigDecimal baseRent) {
        return List.of(new UnitEvents.UnitAddedToProperty(workspaceId, unitId, propertyId, name, baseRent));
    }

    public List<Object> setBaseRent(BigDecimal amount) {
        return List.of(new UnitEvents.UnitBaseRentSet(workspaceId, id, amount));
    }

    public List<Object> updateDetails(Map<String, String> details) {
        return List.of(new UnitEvents.UnitDetailsUpdated(workspaceId, id, details));
    }

    public List<Object> openToRent(String reason) {
        return List.of(new UnitEvents.UnitOpenedToRent(workspaceId, id, reason));
    }

    public List<Object> closeToRent(String reason) {
        return List.of(new UnitEvents.UnitClosedToRent(workspaceId, id, reason));
    }

    public List<Object> remove(String reason) {
        return List.of(new UnitEvents.UnitRemovedFromProperty(workspaceId, id, reason));
    }

    public static Unit from(List<Object> events) {
        var unit = new Unit();
        events.forEach(unit::apply);
        return unit;
    }

    /** Package-private so Task 3's calendar can extend the same rebuild loop. */
    void apply(Object event) {
        switch (event) {
            case UnitEvents.UnitAddedToProperty e -> {
                id = e.unitId();
                workspaceId = e.workspaceId();
                propertyId = e.propertyId();
                name = e.name();
                baseRent = e.baseRent();
                marketState = MarketState.INVENTORY;
            }
            case UnitEvents.UnitBaseRentSet e -> baseRent = e.baseRent();
            case UnitEvents.UnitDetailsUpdated e -> {
                if (e.details().containsKey("listingRef")) {
                    listingRef = e.details().get("listingRef");
                }
            }
            case UnitEvents.UnitOpenedToRent e -> marketState = MarketState.OPEN;
            case UnitEvents.UnitClosedToRent e -> marketState = MarketState.CLOSED;
            case UnitEvents.UnitRemovedFromProperty e -> marketState = MarketState.REMOVED;
            default -> throw new IllegalArgumentException("Unknown event: " + event.getClass());
        }
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID propertyId() {
        return propertyId;
    }

    public String name() {
        return name;
    }

    public BigDecimal baseRent() {
        return baseRent;
    }

    public MarketState marketState() {
        return marketState;
    }

    public String listingRef() {
        return listingRef;
    }
}
