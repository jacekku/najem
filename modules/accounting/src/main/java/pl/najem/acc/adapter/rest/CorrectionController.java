package pl.najem.acc.adapter.rest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.application.CorrectionService;
import pl.najem.acc.application.PaymentNotFoundException;
import pl.najem.acc.domain.PaymentAlreadyReversedException;
import pl.najem.acc.domain.ReversedPaymentHasNothingToMoveException;

import java.util.Map;
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
                        @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        corrections.reverse(workspaceId, paymentId, request.reason());
    }

    @PostMapping("/payments/{paymentId}/amend")
    public void amend(@PathVariable UUID paymentId,
                      @RequestBody AmendmentRequest request,
                      @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        corrections.amendAllocation(workspaceId, paymentId, request.tenancyId(),
            request.reason());
    }

    /**
     * A payment outside the caller's workspace does not exist, rather than being forbidden — so the
     * boundary answers 404 and says nothing about whether the id is real somewhere else.
     */
    @ExceptionHandler(PaymentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(PaymentNotFoundException e) {
        return Map.of("error", e.getMessage());
    }

    /**
     * Both refusals are conflicts rather than bad requests: the request was well formed and the
     * payment is simply not in a state that allows it. They used to reach the client as 500, which
     * reads as "we broke" when it means "you cannot do that to this payment".
     */
    @ExceptionHandler({PaymentAlreadyReversedException.class,
                       ReversedPaymentHasNothingToMoveException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(RuntimeException e) {
        return Map.of("error", e.getMessage());
    }
}
