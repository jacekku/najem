package pl.najem.acc.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.application.WarningService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import pl.najem.contracts.web.ActingWorkspace;

/** The manager's view of what the ledger flagged while charging. */
@RestController
@RequestMapping("/api/acc")
public class WarningController {

    private final WarningService warnings;

    public WarningController(WarningService warnings) {
        this.warnings = warnings;
    }

    @GetMapping("/warnings")
    public List<Map<String, Object>> unseen(
        @ActingWorkspace UUID workspaceId) {
        return warnings.unseen(workspaceId).stream()
            .map(w -> Map.<String, Object>of(
                "warningId", w.warningId(),
                "tenancyId", w.tenancyId(),
                "kind", w.kind().wireName(),
                "detail", w.detail()))
            .toList();
    }

    /**
     * Required on this write, optional on the read above. A write with no header modifies books
     * nobody named and returns success; a read with no header merely shows the wrong list.
     * {@code workspaceId} is passed straight through so the fallback is unreachable from a write by
     * construction rather than by the annotation alone.
     *
     * <p>The header is a <strong>stand-in until the workspace is taken from the verified token and checked against the
     * caller's memberships</strong>. It answers "which workspace", never
     * "may this caller touch it".
     */
    @PostMapping("/warnings/{warningId}/seen")
    public void markSeen(@PathVariable UUID warningId,
                         @ActingWorkspace UUID workspaceId) {
        warnings.markSeen(workspaceId, warningId);
    }
}
