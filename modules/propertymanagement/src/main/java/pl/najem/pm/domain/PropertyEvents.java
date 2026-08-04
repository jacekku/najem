package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Property-stream events. First field is always workspaceId (contracts convention, ruling seq 21). */
public final class PropertyEvents {

    private PropertyEvents() {
    }

    public record PropertyCreated(UUID workspaceId, UUID propertyId, String address,
                                  List<Owner> owners) {
    }

    public record PropertyRentTargetSet(UUID workspaceId, UUID propertyId, BigDecimal amount) {
    }

    public record PropertyOwnershipChanged(UUID workspaceId, UUID propertyId, List<Owner> owners) {
    }

    public record PropertyDetailsUpdated(UUID workspaceId, UUID propertyId, Map<String, String> details) {
    }

    /**
     * nextDueOn is stored rather than derived so the deadline survives a change to the statutory
     * interval: an inspection recorded under the old cadence keeps the deadline it was given.
     */
    public record InspectionCompleted(UUID workspaceId, UUID propertyId, InspectionType type,
                                      LocalDate performedOn, LocalDate nextDueOn, String reportDoc,
                                      String findings) {
    }
}
