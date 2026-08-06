package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.adapter.rest.RepairWire.CompleteRequest;
import pl.najem.pm.adapter.rest.RepairWire.RepairReported;
import pl.najem.pm.adapter.rest.RepairWire.ReportRequest;
import pl.najem.pm.application.RepairService;
import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.StatutoryDutyHint;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import pl.najem.contracts.web.ActingWorkspace;

/**
 * Repairs over HTTP. Shapes are {@link RepairWire}.
 *
 * <p>No {@code WorkspaceGuard}. The acting workspace goes to the service, which asks the portfolio
 * whether the caller owns the asset before a repair is hung off it, and asks the repair itself
 * before one is completed.
 */
@RestController
@RequestMapping("/api/pm/repairs")
public class RepairController {

    private final RepairService repairs;
    private final Clock clock;

    public RepairController(RepairService repairs, Clock clock) {
        this.repairs = repairs;
        this.clock = clock;
    }

    @PostMapping
    public RepairReported report(@ActingWorkspace UUID workspaceId,
                                 @RequestBody ReportRequest request) {
        return new RepairReported(repairs.report(workspaceId, scopeOf(request.scope()),
            request.assetId(), request.description(), request.causedByTenancy(),
            hintOf(request.statutoryDutyHint()),
            request.reportedOn() == null ? LocalDate.now(clock) : request.reportedOn()));
    }

    @PostMapping("/{repairId}/complete")
    public void complete(@ActingWorkspace UUID workspaceId,
                         @PathVariable UUID repairId, @RequestBody CompleteRequest request) {
        repairs.complete(workspaceId, repairId, request.completedOn(), request.notes());
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
