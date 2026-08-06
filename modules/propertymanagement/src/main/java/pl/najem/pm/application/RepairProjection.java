package pl.najem.pm.application;

import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.StatutoryDutyHint;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The write side of {@code pm_repair}.
 *
 * <p>A projection with nothing missing: {@code RepairReported} already carries the repair's own id,
 * so every column of this table comes off the Repair stream and a replay rebuilds it identically.
 * That was not true of pm_inspection until its id was put on its event.
 *
 * <p>Write-only, with {@link OpenRepairQuery} beside it for the read — the same split as
 * {@link InspectionProjection} and for the same reason: completing a repair must not put the open
 * list within reach.
 */
public interface RepairProjection {

    void repairReported(UUID repairId, UUID workspaceId, RepairScope scope, UUID assetId,
                        String description, UUID causedByTenancy, StatutoryDutyHint hint,
                        LocalDate reportedOn);

    /**
     * Takes the workspace so the update can be scoped by it, as every projection write in PM is.
     * {@code Repair.complete} has already refused a second completion by the time this is called,
     * so this does not re-decide anything — it records what the aggregate decided.
     */
    void repairCompleted(UUID repairId, UUID workspaceId, LocalDate completedOn);
}
