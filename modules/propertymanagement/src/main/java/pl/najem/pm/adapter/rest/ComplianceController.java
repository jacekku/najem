package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.ComplianceService;
import pl.najem.pm.application.WorkspaceGuard;
import pl.najem.pm.domain.InspectionType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pm")
public class ComplianceController {

    public record InspectionRequest(String type, LocalDate performedOn, String reportDoc,
                                    String findings) {}

    private final ComplianceService compliance;
    private final WorkspaceGuard guard;

    public ComplianceController(ComplianceService compliance, WorkspaceGuard guard) {
        this.compliance = compliance;
        this.guard = guard;
    }

    @PostMapping("/properties/{propertyId}/inspections")
    public Map<String, UUID> record(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                                    @PathVariable UUID propertyId,
                                    @RequestBody InspectionRequest request) {
        guard.requireProperty(workspaceId, propertyId);
        return Map.of("inspectionId", compliance.recordInspection(propertyId,
            typeOf(request.type()), request.performedOn(), request.reportDoc(),
            request.findings()));
    }

    /** A read, but it still names its workspace — there is no fallback left in this module. */
    @GetMapping("/inspections/overdue")
    public List<ComplianceService.OverdueInspection> overdue(
            @RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
            @RequestParam(required = false) LocalDate on) {
        return compliance.overdue(workspaceId, on == null ? LocalDate.now() : on);
    }

    private static InspectionType typeOf(String wireName) {
        if (wireName == null) {
            throw new IllegalArgumentException("type is required");
        }
        return InspectionType.valueOf(wireName.toUpperCase().replace('-', '_'));
    }
}
