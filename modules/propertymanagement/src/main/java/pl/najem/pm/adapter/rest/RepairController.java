package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.RepairService;
import pl.najem.pm.application.WorkspaceGuard;
import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.StatutoryDutyHint;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import pl.najem.contracts.web.ActingWorkspace;

@RestController
@RequestMapping("/api/pm/repairs")
public class RepairController {

    public record ReportRequest(String scope, UUID assetId, String description,
                                UUID causedByTenancy, String statutoryDutyHint,
                                LocalDate reportedOn) {}

    public record CompleteRequest(LocalDate completedOn, String notes) {}

    private final RepairService repairs;
    private final WorkspaceGuard guard;
    private final Clock clock;

    public RepairController(RepairService repairs, WorkspaceGuard guard, Clock clock) {
        this.repairs = repairs;
        this.guard = guard;
        this.clock = clock;
    }

    @PostMapping
    public Map<String, UUID> report(@ActingWorkspace UUID workspaceId,
                                    @RequestBody ReportRequest request) {
        requireAsset(workspaceId, scopeOf(request.scope()), request.assetId());
        return Map.of("repairId", repairs.report(scopeOf(request.scope()), request.assetId(),
            request.description(), request.causedByTenancy(), hintOf(request.statutoryDutyHint()),
            request.reportedOn() == null ? LocalDate.now(clock) : request.reportedOn()));
    }

    @PostMapping("/{repairId}/complete")
    public void complete(@ActingWorkspace UUID workspaceId,
                         @PathVariable UUID repairId, @RequestBody CompleteRequest request) {
        guard.requireRepair(workspaceId, repairId);
        repairs.complete(repairId, request.completedOn(), request.notes());
    }

    /** A repair inherits its workspace from the asset, so the asset is what must be owned. */
    private void requireAsset(UUID workspaceId, RepairScope scope, UUID assetId) {
        if (scope == RepairScope.PROPERTY) {
            guard.requireProperty(workspaceId, assetId);
        } else {
            guard.requireUnit(workspaceId, assetId);
        }
    }

    /** No defaults on either: both decide something the manager has to have said. */
    private static RepairScope scopeOf(String wireName) {
        if (wireName == null) {
            throw new IllegalArgumentException("scope is required");
        }
        return RepairScope.valueOf(wireName.toUpperCase());
    }

    private static StatutoryDutyHint hintOf(String wireName) {
        if (wireName == null) {
            throw new IllegalArgumentException("statutoryDutyHint is required");
        }
        return StatutoryDutyHint.valueOf(wireName.toUpperCase());
    }
}
