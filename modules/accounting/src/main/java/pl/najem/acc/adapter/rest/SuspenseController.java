package pl.najem.acc.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.WorkspaceContext;
import pl.najem.acc.application.SuspenseEntry;
import pl.najem.acc.application.SuspenseService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Money that has not come to rest, and the two ways a manager can settle what it is. */
@RestController
@RequestMapping("/api/acc")
public class SuspenseController {

    /** What a manager says about a line that is not a tenant's payment. */
    public record NonTenantRequest(String reason) {}

    /** What a manager says about a line the ladder could not place. */
    public record ManualAllocationRequest(UUID tenancyId) {}

    private final SuspenseService suspense;

    public SuspenseController(SuspenseService suspense) {
        this.suspense = suspense;
    }

    @GetMapping("/suspense")
    public List<Map<String, Object>> waiting(
        @RequestHeader(value = "X-Workspace-Id", required = false) UUID workspaceId) {
        return suspense.waiting(workspaceOf(workspaceId)).stream()
            .map(SuspenseController::asWire)
            .toList();
    }

    @PostMapping("/payments/{paymentId}/non-tenant")
    public void markNonTenant(@PathVariable UUID paymentId,
                              @RequestBody NonTenantRequest request,
                              @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        suspense.markNonTenant(workspaceId, paymentId, request.reason());
    }

    @PostMapping("/payments/{paymentId}/allocate")
    public void allocate(@PathVariable UUID paymentId,
                         @RequestBody ManualAllocationRequest request,
                         @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        suspense.allocateTo(workspaceId, paymentId, request.tenancyId());
    }

    /** Nulls are ordinary here — a bank that sent no counterparty is not an error. */
    private static Map<String, Object> asWire(SuspenseEntry entry) {
        var wire = new HashMap<String, Object>();
        wire.put("paymentId", entry.paymentId());
        wire.put("externalId", entry.externalId());
        wire.put("amount", entry.amount());
        wire.put("title", entry.title());
        wire.put("counterpartyName", entry.counterpartyName());
        wire.put("direction", entry.direction());
        wire.put("currency", entry.currency());
        wire.put("bookingDate", entry.bookingDate());
        wire.put("daysWaiting", entry.daysWaiting());
        wire.put("age", entry.age().wireName());
        return wire;
    }

    /** Reads only. A write must never reach this — the guard test enforces it for every mapping. */
    private static UUID workspaceOf(UUID header) {
        return header == null ? WorkspaceContext.DEV_WORKSPACE_ID : header;
    }
}
