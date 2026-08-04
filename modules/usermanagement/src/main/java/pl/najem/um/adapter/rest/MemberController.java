package pl.najem.um.adapter.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.um.application.MembershipService;
import pl.najem.um.application.WorkspaceCaller;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/um/workspaces/{workspaceId}/members")
public class MemberController {

    public record RoleRequest(Role role) {}

    private final MembershipService memberships;
    private final WorkspaceCaller caller;

    public MemberController(MembershipService memberships, WorkspaceCaller caller) {
        this.memberships = memberships;
        this.caller = caller;
    }

    @PutMapping("/{userId}")
    public ResponseEntity<Void> changeRole(@PathVariable UUID workspaceId, @PathVariable UUID userId,
                                           @RequestBody RoleRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        caller.resolve(jwt, workspaceId, Role.ADMIN);
        memberships.changeRole(workspaceId, userId, request.role(), LocalDate.now());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@PathVariable UUID workspaceId, @PathVariable UUID userId,
                                       @AuthenticationPrincipal Jwt jwt) {
        caller.resolve(jwt, workspaceId, Role.ADMIN);
        memberships.remove(workspaceId, userId, LocalDate.now());
        return ResponseEntity.noContent().build();
    }
}
