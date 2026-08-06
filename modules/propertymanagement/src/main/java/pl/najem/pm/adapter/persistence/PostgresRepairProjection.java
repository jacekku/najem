package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.RepairProjection;
import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.StatutoryDutyHint;

import java.time.LocalDate;
import java.util.UUID;

/** {@link RepairProjection} over pm_repair. */
@Repository
public class PostgresRepairProjection implements RepairProjection {

    private final JdbcTemplate jdbc;

    public PostgresRepairProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void repairReported(UUID repairId, UUID workspaceId, RepairScope scope, UUID assetId,
                               String description, UUID causedByTenancy, StatutoryDutyHint hint,
                               LocalDate reportedOn) {
        jdbc.update("insert into pm_repair(repair_id, workspace_id, scope, asset_id, description, "
                + "caused_by_tenancy, statutory_duty_hint, reported_on) values (?,?,?,?,?,?,?,?)",
            repairId, workspaceId, scope.name(), assetId, description, causedByTenancy,
            hint.name(), reportedOn);
    }

    @Override
    public void repairCompleted(UUID repairId, UUID workspaceId, LocalDate completedOn) {
        jdbc.update("update pm_repair set completed_on = ? where repair_id = ? "
            + "and workspace_id = ?", completedOn, repairId, workspaceId);
    }
}
