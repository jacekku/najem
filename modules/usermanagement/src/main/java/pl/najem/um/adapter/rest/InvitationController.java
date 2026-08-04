package pl.najem.um.adapter.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.um.adapter.security.CurrentUser;
import pl.najem.um.application.InvitationService;
import pl.najem.um.application.WorkspaceCaller;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/um")
public class InvitationController {

    public record InviteRequest(String email, Role role, LocalDate expiresOn) {}
    public record AcceptRequest(String token) {}

    private final InvitationService invitations;
    private final CurrentUser currentUser;
    private final WorkspaceCaller caller;

    public InvitationController(InvitationService invitations, CurrentUser currentUser,
                                WorkspaceCaller caller) {
        this.invitations = invitations;
        this.currentUser = currentUser;
        this.caller = caller;
    }

    @PostMapping("/workspaces/{workspaceId}/invitations")
    public Map<String, Object> invite(@PathVariable UUID workspaceId,
                                      @RequestBody InviteRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        UUID invitedBy = caller.resolve(jwt, workspaceId, Role.ADMIN);
        var issued = invitations.invite(workspaceId, request.email(), request.role(), invitedBy,
            LocalDate.now(), request.expiresOn());
        return Map.of("invitationId", issued.invitationId(), "token", issued.token());
    }

    @DeleteMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID workspaceId, @PathVariable UUID invitationId,
                                       @AuthenticationPrincipal Jwt jwt) {
        caller.resolve(jwt, workspaceId, Role.ADMIN);
        invitations.revoke(workspaceId, invitationId, LocalDate.now());
        return ResponseEntity.noContent().build();
    }

    /** Public by design: the invitation token is the credential, and the invitee has no account yet. */
    @PostMapping("/invitations/accept")
    public Map<String, UUID> accept(@RequestBody AcceptRequest request) {
        return Map.of("userId", invitations.accept(request.token(), LocalDate.now()));
    }
}
