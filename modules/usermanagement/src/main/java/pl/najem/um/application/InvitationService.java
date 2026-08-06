package pl.najem.um.application;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.Role;
import pl.najem.um.domain.Workspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Invite-only provisioning (human ruling, najem-build seq 21). Accepting an invitation is the ONLY
 * code path that creates a NAJEM user or a workspace membership.
 *
 * <h2>Why the token exists</h2>
 *
 * <p>{@code /api/um/invitations/accept} is the one endpoint a secured deployment leaves open, and it
 * has to be: the person accepting has no NAJEM account and no Keycloak subject yet — the account is
 * created <em>by</em> accepting. So nothing else can authenticate that request, and this token is
 * the only thing that proves whoever followed the link is who was invited. Handed out in plaintext
 * exactly once and stored only as a SHA-256 hash (D4).
 *
 * <p><b>Nothing delivers it.</b> There is no mailer: {@code InvitationController} returns the token
 * to the inviting admin, who passes it on out of band. That makes {@code um_invitation_recipient}
 * an address which is collected, required at acceptance, and never sent anything.
 */
@Service
@Transactional
public class InvitationService {

    /** The plaintext token is handed out once here and never stored (decision D4). */
    public record Issued(UUID invitationId, String token) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EventStore store;
    private final InvitationRepository invitations;
    private final MembershipProjection memberships;
    private final UserService users;
    private final KeycloakAdminPort keycloak;

    public InvitationService(EventStore store, InvitationRepository invitations,
                             MembershipProjection memberships, UserService users,
                             KeycloakAdminPort keycloak) {
        this.store = store;
        this.invitations = invitations;
        this.memberships = memberships;
        this.users = users;
        this.keycloak = keycloak;
    }

    @PreAuthorize("@caller.isAdminOf(#workspaceId)")
    public Issued invite(UUID workspaceId, String email, Role role, UUID invitedByUserId,
                         LocalDate on, LocalDate expiresOn) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("invitee email is required");
        }
        UUID invitationId = UUID.randomUUID();
        String token = newToken();

        var stream = store.load(workspaceId, "Workspace");
        var events = Workspace.from(UmStreams.workspaceEvents(stream))
            .invite(invitationId, role, invitedByUserId, on, expiresOn);
        store.append(workspaceId, "Workspace", stream.version(), events, List.of());

        invitations.issue(invitationId, workspaceId, role, hash(token), invitedByUserId, email,
            on, expiresOn);
        return new Issued(invitationId, token);
    }

    /**
     * The workspace scopes the invitation, not just the event stream. An admin of one agency holding
     * another's invitation id used to revoke it — while the victim's stream recorded nothing, leaving
     * them an invitation that had silently stopped working and no audit trail saying why. The
     * aggregate only holds its own workspace's invitations, so "unknown here" now covers both
     * not-found and not-yours without this method arranging it.
     */
    @PreAuthorize("@caller.isAdminOf(#workspaceId)")
    public void revoke(UUID workspaceId, UUID invitationId, LocalDate on) {
        var stream = store.load(workspaceId, "Workspace");
        var events = Workspace.from(UmStreams.workspaceEvents(stream))
            .revokeInvitation(invitationId, on);
        store.append(workspaceId, "Workspace", stream.version(), events, List.of());
        invitations.revoke(invitationId);
    }

    /** Returns the accepting user's id, provisioning them in Keycloak on first acceptance. */
    public UUID accept(String token, LocalDate on) {
        // Only the repository can answer this: the token hash and the recipient's address appear in
        // no event, so a stream cannot say which invitation a token names. Everything after this is
        // decided by the aggregate.
        var invitation = invitations.byTokenHash(hash(token))
            .orElseThrow(() -> new IllegalStateException("unknown invitation token"));

        // Whether it may be used is asked before Keycloak is called, so a spent or expired link is
        // refused for its own reason rather than for whatever fails afterwards, and so accepting
        // twice does not provision anybody a second time.
        var stream = store.load(invitation.workspaceId(), "Workspace");
        var workspace = Workspace.from(UmStreams.workspaceEvents(stream));
        workspace.requireInvitationOpen(invitation.invitationId(), on);
        if (invitation.email() == null) {
            throw new IllegalStateException("invitation has no recipient on record");
        }

        UUID subject = keycloak.provision(invitation.email());
        UUID userId = users.findBySubject(subject).orElseGet(() -> users.register(subject, on));

        var events = workspace.acceptInvitation(invitation.invitationId(), userId, on);
        store.append(invitation.workspaceId(), "Workspace", stream.version(), events, List.of());

        memberships.join(invitation.workspaceId(), userId, invitation.role(), on);
        invitations.accept(invitation.invitationId(), userId);
        return userId;
    }

    public static String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
