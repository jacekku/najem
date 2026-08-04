package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.Repair;
import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.StatutoryDutyHint;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Repairs against a property or a unit. Nothing here reaches Accounting: recharging a repair is a
 * manual decision in the MVP (domain model §2 item 16), so there is no integration event.
 */
@Service
@Transactional
public class RepairService {

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final PortfolioService portfolio;

    public RepairService(EventStore store, JdbcTemplate jdbc, PortfolioService portfolio) {
        this.store = store;
        this.jdbc = jdbc;
        this.portfolio = portfolio;
    }

    /** The workspace comes from the asset, never from the caller — same rule as units. */
    public UUID report(RepairScope scope, UUID assetId, String description, UUID causedByTenancy,
                       StatutoryDutyHint hint, LocalDate reportedOn) {
        UUID workspaceId = workspaceOfAsset(scope, assetId);
        UUID repairId = UUID.randomUUID();
        var events = Repair.report(repairId, workspaceId, scope, assetId, description,
            causedByTenancy, hint, reportedOn);
        store.append(repairId, "Repair", 0, events, List.of());
        jdbc.update("insert into pm_repair(repair_id, workspace_id, scope, asset_id, description, "
                + "caused_by_tenancy, statutory_duty_hint, reported_on) values (?,?,?,?,?,?,?,?)",
            repairId, workspaceId, scope.name(), assetId, description, causedByTenancy,
            hint.name(), reportedOn);
        return repairId;
    }

    public void complete(UUID repairId, LocalDate on, String notes) {
        var stream = store.load(repairId, "Repair");
        store.append(repairId, "Repair", stream.version(),
            Repair.from(stream.events()).complete(on, notes), List.of());
        jdbc.update("update pm_repair set completed_on = ? where repair_id = ?", on, repairId);
    }

    private UUID workspaceOfAsset(RepairScope scope, UUID assetId) {
        if (scope == null || assetId == null) {
            throw new IllegalArgumentException("A repair needs an explicit scope and asset");
        }
        return scope == RepairScope.PROPERTY
            ? portfolio.workspaceOf(assetId)
            : portfolio.workspaceOfUnit(assetId);
    }
}
