package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Identity and ownership of a building. workspaceId is fixed at creation and never changes. */
public class Property {

    private UUID id;
    private UUID workspaceId;
    private String address;
    private List<Owner> owners = List.of();
    private BigDecimal rentTarget;
    /** Latest statutory deadline per type; a re-inspection moves it. */
    private final Map<InspectionType, LocalDate> nextDueByType = new EnumMap<>(InspectionType.class);

    private Property() {
    }

    public static List<Object> create(UUID propertyId, UUID workspaceId, String address,
                                      List<Owner> owners) {
        return List.of(new PropertyEvents.PropertyCreated(workspaceId, propertyId, address, owners));
    }

    public List<Object> setRentTarget(BigDecimal amount) {
        return List.of(new PropertyEvents.PropertyRentTargetSet(workspaceId, id, amount));
    }

    public List<Object> changeOwnership(List<Owner> newOwners) {
        return List.of(new PropertyEvents.PropertyOwnershipChanged(workspaceId, id, newOwners));
    }

    public List<Object> updateDetails(Map<String, String> details) {
        return List.of(new PropertyEvents.PropertyDetailsUpdated(workspaceId, id, details));
    }

    /** Soft check: shares should total 100% — confirm, don't block. */
    /**
     * Statutory inspection (art. 62). The next deadline is computed from the type's interval and
     * carried on the event, so the property can answer "what is overdue" without knowing the
     * statute at read time.
     */
    public List<Object> recordInspection(UUID inspectionId, InspectionType type,
                                         LocalDate performedOn, String reportDoc, String findings) {
        if (type == null) {
            throw new IllegalArgumentException("An inspection needs an explicit type");
        }
        return List.of(new PropertyEvents.InspectionCompleted(workspaceId, id, inspectionId, type,
            performedOn, type.nextDue(performedOn), reportDoc, findings));
    }

    /** Empty when this type has never been inspected — absence is not an overdue deadline. */
    public Optional<LocalDate> nextDue(InspectionType type) {
        return Optional.ofNullable(nextDueByType.get(type));
    }

    public Warnings warnings() {
        var warnings = new Warnings();
        var total = owners.stream().map(Owner::sharePercent).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100")) != 0) {
            warnings.add("Ownership shares total " + total.stripTrailingZeros().toPlainString()
                + ", expected 100");
        }
        return warnings;
    }

    /**
     * Refuses a caller who does not own this property. The unit's rule, on the parent — see
     * {@link Unit#requireOwnedBy(UUID)} for why the stream answers this rather than the projection.
     *
     * <p>It also supplies what a child needs: {@code addUnit} must stamp the new unit with its
     * parent's workspace, and asking the parent to confirm the caller owns it is the same load. One
     * read that both checks and answers, rather than a guard query and a rebuild that could differ.
     */
    public void requireOwnedBy(UUID caller) {
        if (caller == null || workspaceId == null || !workspaceId.equals(caller)) {
            throw new UnknownInThisWorkspaceException("property " + id);
        }
    }

    public static Property from(List<Object> events) {
        var property = new Property();
        events.forEach(property::apply);
        return property;
    }

    private void apply(Object event) {
        switch (event) {
            case PropertyEvents.InspectionCompleted e ->
                nextDueByType.put(e.type(), e.nextDueOn());
            case PropertyEvents.PropertyCreated e -> {
                id = e.propertyId();
                workspaceId = e.workspaceId();
                address = e.address();
                owners = e.owners();
            }
            case PropertyEvents.PropertyRentTargetSet e -> rentTarget = e.amount();
            case PropertyEvents.PropertyOwnershipChanged e -> owners = e.owners();
            case PropertyEvents.PropertyDetailsUpdated e -> { }
            default -> throw new IllegalArgumentException("Unknown event: " + event.getClass());
        }
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public String address() {
        return address;
    }

    public List<Owner> owners() {
        return owners;
    }

    public BigDecimal rentTarget() {
        return rentTarget;
    }
}
