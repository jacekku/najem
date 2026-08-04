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
import pl.najem.contacts.application.RetentionService;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
public class RetentionController {

    public record HoldRequest(String reason) {
    }

    private final RetentionService retention;
    private final Clock clock;

    public RetentionController(RetentionService retention, Clock clock) {
        this.retention = retention;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    @PostMapping("/{contactId}/retention-holds")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setHold(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                        @PathVariable UUID contactId, @RequestBody HoldRequest request) {
        retention.setHold(workspaceId, contactId, request.reason(), today());
    }

    @DeleteMapping("/{contactId}/retention-holds/{reason}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseHold(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                            @PathVariable UUID contactId, @PathVariable String reason,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        retention.releaseHold(workspaceId, contactId, reason, on == null ? today() : on);
    }

    @GetMapping("/erasure-due")
    public List<UUID> dueForErasure(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return retention.dueForErasure(workspaceId, asOf == null ? today() : asOf);
    }
}
