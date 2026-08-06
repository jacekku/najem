package pl.najem.pm.application;

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
 *
 * <p>The workspace still comes from the caller and the asset still has to be owned by them, but
 * both used to be established twice. {@code report} was guarded against pm_property or pm_unit in
 * the controller and then asked {@code PortfolioService} to rebuild the same aggregate for its
 * workspace; {@code complete} was guarded against pm_repair and then rebuilt the Repair for the
 * same fact. Now the portfolio vouches for the asset once, and the repair answers for itself.
 */
@Service
@Transactional
public class RepairService {

    private final EventStore store;
    private final RepairProjection projection;
    private final PortfolioService portfolio;

    public RepairService(EventStore store, RepairProjection projection, PortfolioService portfolio) {
        this.store = store;
        this.projection = projection;
        this.portfolio = portfolio;
    }

    /**
     * The asset must be owned by the caller before anything is hung off it, and the repair inherits
     * that workspace — the same rule as a unit inheriting its property's.
     */
    public UUID report(UUID workspaceId, RepairScope scope, UUID assetId, String description,
                       UUID causedByTenancy, StatutoryDutyHint hint, LocalDate reportedOn) {
        requireOwnedAsset(workspaceId, scope, assetId);
        UUID repairId = UUID.randomUUID();
        var events = Repair.report(repairId, workspaceId, scope, assetId, description,
            causedByTenancy, hint, reportedOn);
        store.append(repairId, "Repair", 0, events, List.of());
        projection.repairReported(repairId, workspaceId, scope, assetId, description,
            causedByTenancy, hint, reportedOn);
        return repairId;
    }

    public void complete(UUID workspaceId, UUID repairId, LocalDate on, String notes) {
        var stream = store.load(repairId, "Repair");
        var repair = Repair.from(stream.events());
        repair.requireOwnedBy(workspaceId);
        store.append(repairId, "Repair", stream.version(), repair.complete(on, notes), List.of());
        projection.repairCompleted(repairId, workspaceId, on);
    }

    /**
     * Mapping a scope onto a property or a unit is done here because the scope is this service's
     * vocabulary. {@link PortfolioService} is asked whether the caller owns a property or owns a
     * unit, and is not told that repairs exist.
     */
    private void requireOwnedAsset(UUID workspaceId, RepairScope scope, UUID assetId) {
        if (scope == null || assetId == null) {
            throw new IllegalArgumentException("A repair needs an explicit scope and asset");
        }
        if (scope == RepairScope.PROPERTY) {
            portfolio.requireOwnsProperty(workspaceId, assetId);
        } else {
            portfolio.requireOwnsUnit(workspaceId, assetId);
        }
    }
}
