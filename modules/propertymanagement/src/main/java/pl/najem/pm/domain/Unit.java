package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private final List<TenancyPeriod> periods = new ArrayList<>();

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

    /**
     * THE hard invariant. Note what is deliberately NOT checked: market state. Reserving a
     * closed unit is allowed (expert freedom — you sign for a flat still under construction).
     *
     * <p>Safety under concurrency does not come from this check alone. The caller appends the
     * resulting event to the Unit stream at the version it read, so two simultaneous
     * reservations collide on the event store's unique(stream_id, version) and one loses.
     */
    public List<Object> registerTenancyPeriod(UUID tenancyId, LocalDate start, LocalDate end) {
        var candidate = new TenancyPeriod(tenancyId, start, end);
        for (TenancyPeriod existing : periods) {
            if (existing.overlaps(candidate)) {
                throw new OverlappingTenancyException("Tenancy period " + start + ".." + end
                    + " overlaps tenancy " + existing.tenancyId() + " (" + existing.start()
                    + ".." + existing.end() + ") on unit " + id);
            }
        }
        return List.of(new UnitEvents.TenancyPeriodRegistered(workspaceId, id, tenancyId, start, end));
    }

    public List<Object> releaseTenancyPeriod(UUID tenancyId) {
        return List.of(new UnitEvents.TenancyPeriodReleased(workspaceId, id, tenancyId));
    }

    public List<TenancyPeriod> periods() {
        return List.copyOf(periods);
    }

    /**
     * Refuses a caller who does not own this unit.
     *
     * <p>Asked of the unit rebuilt from its own stream, which is the record. The projection row
     * carries the same workspace and was the thing consulted before, but it is written by a second
     * statement after the append — so the two could differ, and a check against the derived copy is
     * a check against something that is allowed to lag. There is one authoritative answer and this
     * is it.
     *
     * <p>Fails closed on a null caller and on a unit no event has created: absence and foreign
     * ownership are the same answer, because saying which would confirm another agency's id is
     * real.
     */
    public void requireOwnedBy(UUID caller) {
        if (caller == null || workspaceId == null || !workspaceId.equals(caller)) {
            throw new UnknownInThisWorkspaceException("unit " + id);
        }
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
            case UnitEvents.TenancyPeriodRegistered e ->
                periods.add(new TenancyPeriod(e.tenancyId(), e.start(), e.end()));
            case UnitEvents.TenancyPeriodReleased e ->
                periods.removeIf(p -> p.tenancyId().equals(e.tenancyId()));
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
