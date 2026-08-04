package pl.najem.acc.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.WorkspaceContext;
import pl.najem.acc.application.WarningService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        @RequestHeader(value = "X-Workspace-Id", required = false) UUID workspaceId) {
        return warnings.unseen(workspaceOf(workspaceId)).stream()
            .map(w -> Map.<String, Object>of(
                "warningId", w.warningId(),
                "tenancyId", w.tenancyId(),
                "kind", w.kind().wireName(),
                "detail", w.detail()))
            .toList();
    }

    @PostMapping("/warnings/{warningId}/seen")
    public void markSeen(@PathVariable UUID warningId,
                         @RequestHeader(value = "X-Workspace-Id", required = false) UUID workspaceId) {
        warnings.markSeen(workspaceOf(workspaceId), warningId);
    }

    private static UUID workspaceOf(UUID header) {
        return header == null ? WorkspaceContext.DEV_WORKSPACE_ID : header;
    }
}
