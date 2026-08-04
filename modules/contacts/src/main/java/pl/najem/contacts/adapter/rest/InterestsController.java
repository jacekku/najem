package pl.najem.contacts.adapter.rest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.contacts.application.Interest;
import pl.najem.contacts.application.InterestService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
public class InterestsController {

    public record RegisterInterestRequest(UUID unitId, BigDecimal willingToPay, LocalDate desiredStart) {
    }

    private final InterestService interests;
    private final Clock clock;

    public InterestsController(InterestService interests, Clock clock) {
        this.interests = interests;
        this.clock = clock;
    }

    @PostMapping("/{contactId}/interests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> register(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                                      @PathVariable UUID contactId,
                                      @RequestBody RegisterInterestRequest request) {
        return Map.of("interestId", interests.register(workspaceId, contactId,
            request.unitId(), request.willingToPay(), request.desiredStart()));
    }

    @DeleteMapping("/interests/{interestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                         @PathVariable UUID interestId,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        interests.withdraw(workspaceId, interestId, on == null ? LocalDate.now(clock) : on);
    }

    @GetMapping("/units/{unitId}/interests")
    public List<Interest> forUnit(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                                  @PathVariable UUID unitId) {
        return interests.forUnit(workspaceId, unitId);
    }
}
