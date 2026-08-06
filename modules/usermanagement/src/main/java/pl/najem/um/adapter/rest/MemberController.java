package pl.najem.um.adapter.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.um.application.MembershipService;
import pl.najem.um.domain.Role;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * No authorization here, and that is the design. {@code MembershipService} carries
 * {@code @PreAuthorize}, so the rule holds for any caller of the use case rather than for this one
 * entry point (A1) — and a new endpoint cannot reach the operation unguarded by forgetting a line.
 */
@RestController
@RequestMapping("/api/um/workspaces/{workspaceId}/members")
public class MemberController {

    public record RoleRequest(Role role) {}

    private final MembershipService memberships;
    private final Clock clock;

    public MemberController(MembershipService memberships, Clock clock) {
        this.memberships = memberships;
        this.clock = clock;
    }

    @PutMapping("/{userId}")
    public ResponseEntity<Void> changeRole(@PathVariable UUID workspaceId, @PathVariable UUID userId,
                                           @RequestBody RoleRequest request) {
        memberships.changeRole(workspaceId, userId, request.role(), LocalDate.now(clock));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@PathVariable UUID workspaceId, @PathVariable UUID userId) {
        memberships.remove(workspaceId, userId, LocalDate.now(clock));
        return ResponseEntity.noContent().build();
    }
}
