package pl.najem.pm.application;

import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.StatutoryDutyHint;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link RepairProjection} and {@link OpenRepairQuery} over one map of rows, as pm_repair is one
 * table. The ports stay separate where it counts — a service is handed only the one it needs.
 */
public class InMemoryRepairs implements RepairProjection, OpenRepairQuery {

    public record Row(UUID repairId, UUID workspaceId, RepairScope scope, UUID assetId,
                      String description, UUID causedByTenancy, StatutoryDutyHint hint,
                      LocalDate reportedOn, LocalDate completedOn) {}

    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    @Override
    public void repairReported(UUID repairId, UUID workspaceId, RepairScope scope, UUID assetId,
                               String description, UUID causedByTenancy, StatutoryDutyHint hint,
                               LocalDate reportedOn) {
        if (rows.putIfAbsent(repairId, new Row(repairId, workspaceId, scope, assetId, description,
                causedByTenancy, hint, reportedOn, null)) != null) {
            throw new IllegalStateException("duplicate key on pm_repair: " + repairId);
        }
    }

    /**
     * {@code where repair_id = ? and workspace_id = ?}: a mismatch matches no row and changes
     * nothing, silently, exactly as the statement does.
     */
    @Override
    public void repairCompleted(UUID repairId, UUID workspaceId, LocalDate completedOn) {
        var row = rows.get(repairId);
        if (row == null || !row.workspaceId().equals(workspaceId)) {
            return;
        }
        rows.put(repairId, new Row(row.repairId(), row.workspaceId(), row.scope(), row.assetId(),
            row.description(), row.causedByTenancy(), row.hint(), row.reportedOn(), completedOn));
    }

    @Override
    public List<OpenRepair> openRepairs(UUID workspaceId) {
        return rows.values().stream()
            .filter(row -> row.workspaceId().equals(workspaceId))
            .filter(row -> row.completedOn() == null)
            .sorted(Comparator.comparing(Row::reportedOn))
            .map(row -> new OpenRepair(row.repairId(), row.scope().name(), row.assetId(),
                row.description(), row.hint().name(), row.reportedOn()))
            .toList();
    }

    public Optional<Row> repair(UUID repairId) {
        return Optional.ofNullable(rows.get(repairId));
    }
}
