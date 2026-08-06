package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.AttentionListsService;
import pl.najem.pm.application.TenancyAttentionRow;
import pl.najem.pm.application.ComplianceService;
import pl.najem.pm.application.OpenRepair;
import pl.najem.pm.application.OverdueInspection;
import pl.najem.pm.application.TenancyService;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import pl.najem.contracts.web.ActingWorkspace;

/**
 * What PM puts in front of a manager. This is the half of the Unit Board that PM owns: the
 * warnings are DOMAIN RULES, not stored facts, so nobody else can compose them without
 * reimplementing them — the same reason arrears colour belongs to accounting.
 *
 * <p>Occupancy is deliberately absent. Reporting serves that from PM's event streams.
 */
@RestController
@RequestMapping("/api/pm/attention")
public class AttentionController {

    private final AttentionListsService attention;
    private final ComplianceService compliance;
    private final TenancyService tenancies;
    private final Clock clock;

    public AttentionController(AttentionListsService attention, ComplianceService compliance,
                               TenancyService tenancies, Clock clock) {
        this.attention = attention;
        this.compliance = compliance;
        this.tenancies = tenancies;
        this.clock = clock;
    }

    @GetMapping("/starting-soon")
    public List<TenancyAttentionRow> startingSoon(
            @ActingWorkspace UUID workspaceId,
            @RequestParam(required = false) LocalDate on) {
        return attention.startingSoon(workspaceId, orToday(on));
    }

    @GetMapping("/ending-soon")
    public List<TenancyAttentionRow> endingSoon(
            @ActingWorkspace UUID workspaceId,
            @RequestParam(required = false) LocalDate on) {
        return attention.endingSoon(workspaceId, orToday(on));
    }

    @GetMapping("/insurance-expiring")
    public List<TenancyAttentionRow> insuranceExpiring(
            @ActingWorkspace UUID workspaceId,
            @RequestParam(required = false) LocalDate on) {
        return attention.insuranceExpiring(workspaceId, orToday(on));
    }

    @GetMapping("/repairs-open")
    public List<OpenRepair> openRepairs(
            @ActingWorkspace UUID workspaceId) {
        return attention.openRepairs(workspaceId);
    }

    @GetMapping("/inspections-overdue")
    public List<OverdueInspection> overdueInspections(
            @ActingWorkspace UUID workspaceId,
            @RequestParam(required = false) LocalDate on) {
        return compliance.overdue(workspaceId, orToday(on));
    }

    /**
     * The soft checks on one tenancy — deposit over the statutory cap, ownership shares not
     * summing to 100%, a term over ten years, a unilateral increase under three months' notice,
     * a field corrected after Accounting was told the old value.
     *
     * <p>Computed from the aggregate on every call rather than stored, because they are rules
     * over current state: a stored warning goes stale the moment the state it judged changes.
     * Callers render these verbatim and must never recompute them.
     */
    @GetMapping("/tenancies/{tenancyId}/warnings")
    public Map<String, List<String>> warnings(
            @ActingWorkspace UUID workspaceId,
            @PathVariable UUID tenancyId) {
        return Map.of("warnings", tenancies.warnings(workspaceId, tenancyId));
    }

    private LocalDate orToday(LocalDate on) {
        return on == null ? LocalDate.now(clock) : on;
    }
}
