package pl.najem.acc.adapter.rest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.application.DepositNotHeldException;
import pl.najem.acc.application.DepositService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import pl.najem.contracts.web.ActingWorkspace;

@RestController
@RequestMapping("/api/acc")
public class DepositController {

    /**
     * @param rentAtReturn the czynsz in force on the day of return, which art. 6 ust. 4 valorizes
     *                     against — it is not derivable from anything accounting holds, because the
     *                     rent may have moved since the last charge was posted
     */
    public record SettlementRequest(BigDecimal rentAtReturn, LocalDate returnedOn) {}

    private final DepositService deposits;

    public DepositController(DepositService deposits) {
        this.deposits = deposits;
    }

    @PostMapping("/tenancies/{tenancyId}/deposit/settle")
    public Map<String, BigDecimal> settle(@PathVariable UUID tenancyId,
                                          @ActingWorkspace UUID workspaceId,
                                          @RequestBody SettlementRequest request) {
        return Map.of("returned", deposits.settle(workspaceId, tenancyId, request.rentAtReturn(),
            request.returnedOn()));
    }

    /**
     * 409 rather than 404: the tenancy exists and the caller may see it. What does not exist is a
     * deposit to give back, and saying so is more useful than a bare not-found.
     */
    @ExceptionHandler(DepositNotHeldException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> notHeld(DepositNotHeldException e) {
        return Map.of("error", e.getMessage());
    }
}
