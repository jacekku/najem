package pl.najem.um.adapter.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.um.application.InvitationService;
import pl.najem.um.domain.Role;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/um")
public class InvitationController {

    public record InviteRequest(String email, Role role, LocalDate expiresOn) {}
    public record AcceptRequest(String token) {}

    private final InvitationService invitations;
    private final Clock clock;

    public InvitationController(InvitationService invitations, Clock clock) {
        this.invitations = invitations;
        this.clock = clock;
    }

    /**
     * {@code invitedBy} is injected, not read from the request — an inviter who cannot state who
     * they are cannot state somebody else. Whether they may invite is {@code InvitationService}'s
     * {@code @PreAuthorize}, a separate question decided in a separate place.
     */
    @PostMapping("/workspaces/{workspaceId}/invitations")
    public Map<String, Object> invite(@PathVariable UUID workspaceId,
                                      @RequestBody InviteRequest request,
                                      @ActingUser UUID invitedBy) {
        var issued = invitations.invite(workspaceId, request.email(), request.role(), invitedBy,
            LocalDate.now(clock), request.expiresOn());
        return Map.of("invitationId", issued.invitationId(), "token", issued.token());
    }

    @DeleteMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID workspaceId, @PathVariable UUID invitationId) {
        invitations.revoke(workspaceId, invitationId, LocalDate.now(clock));
        return ResponseEntity.noContent().build();
    }

    /** Public by design: the invitation token is the credential, and the invitee has no account yet. */
    @PostMapping("/invitations/accept")
    public Map<String, UUID> accept(@RequestBody AcceptRequest request) {
        return Map.of("userId", invitations.accept(request.token(), LocalDate.now(clock)));
    }
}
