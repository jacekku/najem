package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.InvitationAccepted;
import pl.najem.um.domain.InvitationRevoked;
import pl.najem.um.domain.MemberInvited;
import pl.najem.um.domain.Role;

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
 */
@Service
@Transactional
public class InvitationService {

    /** The plaintext token is handed out once here and never stored (decision D4). */
    public record Issued(UUID invitationId, String token) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final KeycloakAdminPort keycloak;

    public InvitationService(EventStore store, JdbcTemplate jdbc, UserService users,
                             KeycloakAdminPort keycloak) {
        this.store = store;
        this.jdbc = jdbc;
        this.users = users;
        this.keycloak = keycloak;
    }

    public Issued invite(UUID workspaceId, String email, Role role, UUID invitedByUserId,
                         LocalDate on, LocalDate expiresOn) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("invitee email is required");
        }
        if (expiresOn.isBefore(on)) {
            throw new IllegalArgumentException("invitation cannot expire before it is issued");
        }
        UUID invitationId = UUID.randomUUID();
        String token = newToken();

        var stream = store.load(workspaceId, "Workspace");
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new MemberInvited(workspaceId, invitationId, role, invitedByUserId, on, expiresOn)),
            List.of());

        jdbc.update("""
            insert into um_invitation(invitation_id, workspace_id, role, token_hash, status,
                                      invited_by_user_id, issued_on, expires_on)
            values (?,?,?,?,'PENDING',?,?,?)
            """, invitationId, workspaceId, role.name(), hash(token), invitedByUserId, on, expiresOn);
        jdbc.update("insert into um_invitation_recipient(invitation_id, email) values (?,?)",
            invitationId, email);
        return new Issued(invitationId, token);
    }

    public void revoke(UUID workspaceId, UUID invitationId, LocalDate on) {
        // Scope the row by workspace, and check before appending. The workspace used to pick the
        // event stream and nothing else, so an admin of one agency holding another's invitation
        // id revoked it -- while the victim's stream recorded nothing, leaving them an invitation
        // that had silently stopped working and no audit trail saying why.
        int revoked = jdbc.update("""
            update um_invitation set status = 'REVOKED'
            where invitation_id = ? and workspace_id = ? and status = 'PENDING'
            """, invitationId, workspaceId);
        if (revoked == 0) {
            // Not-found and not-yours are deliberately the same answer: telling the caller which
            // one it was confirms the existence of an invitation in someone else's workspace.
            throw new IllegalStateException("no pending invitation " + invitationId + " in this workspace");
        }
        jdbc.update("delete from um_invitation_recipient where invitation_id = ?", invitationId);
        var stream = store.load(workspaceId, "Workspace");
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new InvitationRevoked(workspaceId, invitationId, on)), List.of());
    }

    /** Returns the accepting user's id, provisioning them in Keycloak on first acceptance. */
    public UUID accept(String token, LocalDate on) {
        var pending = jdbc.query("""
            select i.invitation_id, i.workspace_id, i.role, i.status, i.expires_on, r.email
            from um_invitation i
            left join um_invitation_recipient r on r.invitation_id = i.invitation_id
            where i.token_hash = ?
            """, (rs, i) -> new Pending(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), Role.valueOf(rs.getString(3)),
                rs.getString(4), rs.getObject(5, LocalDate.class), rs.getString(6)),
            hash(token));
        if (pending.isEmpty()) {
            throw new IllegalStateException("unknown invitation token");
        }
        var invitation = pending.getFirst();
        if (!"PENDING".equals(invitation.status())) {
            throw new IllegalStateException("invitation is not pending: " + invitation.status());
        }
        if (on.isAfter(invitation.expiresOn())) {
            throw new IllegalStateException("invitation expired on " + invitation.expiresOn());
        }
        if (invitation.email() == null) {
            throw new IllegalStateException("invitation has no recipient on record");
        }

        UUID subject = keycloak.provision(invitation.email());
        UUID userId = users.findBySubject(subject).orElseGet(() -> users.register(subject, on));

        var stream = store.load(invitation.workspaceId(), "Workspace");
        store.append(invitation.workspaceId(), "Workspace", stream.version(),
            List.of(new InvitationAccepted(invitation.workspaceId(), invitation.invitationId(), userId, on)),
            List.of());

        jdbc.update("""
            insert into um_membership(workspace_id, user_id, role, joined_on) values (?,?,?,?)
            on conflict (workspace_id, user_id) do update set role = excluded.role
            """, invitation.workspaceId(), userId, invitation.role().name(), on);
        jdbc.update("update um_invitation set status = 'ACCEPTED', accepted_by_user_id = ? where invitation_id = ?",
            userId, invitation.invitationId());
        jdbc.update("delete from um_invitation_recipient where invitation_id = ?", invitation.invitationId());
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

    private record Pending(UUID invitationId, UUID workspaceId, Role role, String status,
                           LocalDate expiresOn, String email) {}
}
