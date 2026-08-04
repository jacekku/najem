package pl.najem.acc.adapter.rest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.WorkspaceContext;
import pl.najem.acc.application.IngestionService;
import pl.najem.acc.application.ReconciliationService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/acc")
public class ReconciliationController {

    private final IngestionService ingestion;
    private final ReconciliationService reconciliation;
    private final JdbcTemplate jdbc;

    public ReconciliationController(IngestionService ingestion, ReconciliationService reconciliation,
                                    JdbcTemplate jdbc) {
        this.ingestion = ingestion;
        this.reconciliation = reconciliation;
        this.jdbc = jdbc;
    }

    /**
     * The workspace header is required on writes and optional on reads, and the asymmetry is the
     * point: a read with no header shows the wrong (empty) data and the caller notices, while a
     * write with no header puts real data into books nobody named and returns success. Per-workspace
     * uniqueness then means the misplaced rows never collide with the correct ones, so removing the
     * fallback later does not undo what it wrote.
     *
     * <p>Passing {@code workspaceId} straight through rather than via {@code workspaceOf} keeps the
     * fallback unreachable from a write by construction, not merely by the annotation — otherwise
     * making the header optional again silently restores the old behaviour with no change to the body.
     *
     * <p>The header itself is a <strong>stand-in until the workspace is taken from the verified token and checked against the
     * caller's memberships</strong>. Requiring it stops a caller
     * omitting the workspace; nothing here stops a caller naming someone else's. That check does not
     * exist yet, and this annotation must not be read as though it did.
     */
    @PostMapping("/ingest/fetch")
    public void fetch(@RequestHeader("X-Workspace-Id") UUID workspaceId) {
        ingestion.fetchAndIngest(workspaceId);
    }

    @GetMapping("/suggestions")
    public List<Map<String, Object>> suggestions(
        @RequestHeader(value = "X-Workspace-Id", required = false) UUID workspaceId) {
        return jdbc.query("select payment_id, charge_id from acc_suggestion where workspace_id = ?",
            (rs, i) -> Map.of("paymentId", rs.getObject(1), "chargeId", rs.getObject(2)),
            workspaceOf(workspaceId));
    }

    @PostMapping("/payments/{paymentId}/confirm")
    public void confirm(@PathVariable UUID paymentId,
                        @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        reconciliation.confirm(workspaceId, paymentId);
    }

    /** Reads only. A write must never reach this — see {@link #fetch}. */
    private static UUID workspaceOf(UUID header) {
        return header == null ? WorkspaceContext.DEV_WORKSPACE_ID : header;
    }
}
