package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.AttentionListsQuery;
import pl.najem.pm.application.ComplianceService;
import pl.najem.pm.application.TenancyService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    private final AttentionListsQuery attention;
    private final ComplianceService compliance;
    private final TenancyService tenancies;

    public AttentionController(AttentionListsQuery attention, ComplianceService compliance,
                               TenancyService tenancies) {
        this.attention = attention;
        this.compliance = compliance;
        this.tenancies = tenancies;
    }

    @GetMapping("/starting-soon")
    public List<AttentionListsQuery.TenancyAttentionRow> startingSoon(
            @RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
            @RequestParam(required = false) LocalDate on) {
        return attention.startingSoon(workspaceId, orToday(on));
    }

    @GetMapping("/ending-soon")
    public List<AttentionListsQuery.TenancyAttentionRow> endingSoon(
            @RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
            @RequestParam(required = false) LocalDate on) {
        return attention.endingSoon(workspaceId, orToday(on));
    }

    @GetMapping("/insurance-expiring")
    public List<AttentionListsQuery.TenancyAttentionRow> insuranceExpiring(
            @RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
            @RequestParam(required = false) LocalDate on) {
        return attention.insuranceExpiring(workspaceId, orToday(on));
    }

    @GetMapping("/repairs-open")
    public List<AttentionListsQuery.OpenRepairRow> openRepairs(
            @RequestHeader(WorkspaceHeader.NAME) UUID workspaceId) {
        return attention.openRepairs(workspaceId);
    }

    @GetMapping("/inspections-overdue")
    public List<ComplianceService.OverdueInspection> overdueInspections(
            @RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
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
    public Map<String, List<String>> warnings(@PathVariable UUID tenancyId) {
        return Map.of("warnings", tenancies.warnings(tenancyId));
    }

    private static LocalDate orToday(LocalDate on) {
        return on == null ? LocalDate.now() : on;
    }
}
