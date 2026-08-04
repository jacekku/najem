package pl.najem.pm.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Repair-stream events. First field is always workspaceId. */
public final class RepairEvents {

    private RepairEvents() {
    }

    public record RepairReported(UUID workspaceId, UUID repairId, RepairScope scope, UUID assetId,
                                 String description, UUID causedByTenancy,
                                 StatutoryDutyHint statutoryDutyHint, LocalDate reportedOn) {
    }

    public record RepairCompleted(UUID workspaceId, UUID repairId, LocalDate completedOn,
                                  String notes) {
    }
}
