package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.adapter.rest.ComplianceWire.InspectionRecorded;
import pl.najem.pm.adapter.rest.ComplianceWire.InspectionRequest;
import pl.najem.pm.application.ComplianceService;
import pl.najem.pm.application.OverdueInspection;
import pl.najem.pm.domain.InspectionType;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import pl.najem.contracts.web.ActingWorkspace;

/**
 * Statutory inspections over HTTP. Shapes are {@link ComplianceWire}.
 *
 * <p>No {@code WorkspaceGuard}: the acting workspace goes to the service, which checks it against
 * the property's own stream rather than against the pm_property row.
 */
@RestController
@RequestMapping("/api/pm")
public class ComplianceController {

    private final ComplianceService compliance;
    private final Clock clock;

    public ComplianceController(ComplianceService compliance, Clock clock) {
        this.compliance = compliance;
        this.clock = clock;
    }

    @PostMapping("/properties/{propertyId}/inspections")
    public InspectionRecorded record(@ActingWorkspace UUID workspaceId,
                                     @PathVariable UUID propertyId,
                                     @RequestBody InspectionRequest request) {
        return new InspectionRecorded(compliance.recordInspection(workspaceId, propertyId,
            typeOf(request.type()), request.performedOn(), request.reportDoc(),
            request.findings()));
    }

    /** A read, but it still names its workspace — there is no fallback left in this module. */
    @GetMapping("/inspections/overdue")
    public List<OverdueInspection> overdue(
            @ActingWorkspace UUID workspaceId,
            @RequestParam(required = false) LocalDate on) {
        return compliance.overdue(workspaceId, on == null ? LocalDate.now(clock) : on);
    }

    private static InspectionType typeOf(String wireName) {
        if (wireName == null) {
            throw new IllegalArgumentException("type is required");
        }
        return InspectionType.valueOf(wireName.toUpperCase().replace('-', '_'));
    }
}
