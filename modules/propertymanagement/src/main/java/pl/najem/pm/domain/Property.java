package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Identity and ownership of a building. workspaceId is fixed at creation and never changes. */
public class Property {

    private UUID id;
    private UUID workspaceId;
    private String address;
    private List<Owner> owners = List.of();
    private BigDecimal rentTarget;

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
    public Warnings warnings() {
        var warnings = new Warnings();
        var total = owners.stream().map(Owner::sharePercent).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100")) != 0) {
            warnings.add("Ownership shares total " + total.stripTrailingZeros().toPlainString()
                + ", expected 100");
        }
        return warnings;
    }

    public static Property from(List<Object> events) {
        var property = new Property();
        events.forEach(property::apply);
        return property;
    }

    private void apply(Object event) {
        switch (event) {
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
