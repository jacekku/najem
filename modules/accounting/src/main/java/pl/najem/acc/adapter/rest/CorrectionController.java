package pl.najem.acc.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.WorkspaceContext;
import pl.najem.acc.application.CorrectionService;

import java.util.UUID;

/**
 * The two corrections a payment can need. They are separate endpoints on purpose: a reversal and an
 * amendment are opposites, and one flag on one endpoint would make them a click apart.
 */
@RestController
@RequestMapping("/api/acc")
public class CorrectionController {

    /** Why the bank took the money back. */
    public record ReversalRequest(String reason) {}

    /** Which tenancy the money actually belonged to, and why we now think so. */
    public record AmendmentRequest(UUID tenancyId, String reason) {}

    private final CorrectionService corrections;

    public CorrectionController(CorrectionService corrections) {
        this.corrections = corrections;
    }

    @PostMapping("/payments/{paymentId}/reverse")
    public void reverse(@PathVariable UUID paymentId,
                        @RequestBody ReversalRequest request,
                        @RequestHeader(value = "X-Workspace-Id", required = false) UUID workspaceId) {
        corrections.reverse(workspaceOf(workspaceId), paymentId, request.reason());
    }

    @PostMapping("/payments/{paymentId}/amend")
    public void amend(@PathVariable UUID paymentId,
                      @RequestBody AmendmentRequest request,
                      @RequestHeader(value = "X-Workspace-Id", required = false) UUID workspaceId) {
        corrections.amendAllocation(workspaceOf(workspaceId), paymentId, request.tenancyId(),
            request.reason());
    }

    private static UUID workspaceOf(UUID header) {
        return header == null ? WorkspaceContext.DEV_WORKSPACE_ID : header;
    }
}
