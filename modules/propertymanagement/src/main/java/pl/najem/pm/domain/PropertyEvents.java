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
     *
     * <p>inspectionId is on the event because the row it identifies has to be reproducible. It used
     * to be minted beside the insert and carried on nothing, so {@code pm_inspection} was derived
     * from this stream in every column but its primary key — a replay rebuilt the table with
     * different ids, and the id is what the API hands back to a client. A store that cannot be
     * rebuilt identically is not a projection whatever it is called.
     *
     * <p>Payloads written before this component exists deserialize with a null inspectionId. There
     * is no replay of this stream into pm_inspection today, so nothing reads it yet; the reader that
     * one day does has to treat null as "recorded before ids were carried" rather than assume.
     */
    public record InspectionCompleted(UUID workspaceId, UUID propertyId, UUID inspectionId,
                                      InspectionType type, LocalDate performedOn,
                                      LocalDate nextDueOn, String reportDoc, String findings) {
    }
}
