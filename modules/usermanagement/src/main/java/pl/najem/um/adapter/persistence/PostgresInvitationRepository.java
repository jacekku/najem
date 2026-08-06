package pl.najem.um.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.um.application.InvitationRepository;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresInvitationRepository implements InvitationRepository {

    private final JdbcTemplate jdbc;

    public PostgresInvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void issue(UUID invitationId, UUID workspaceId, Role role, String tokenHash,
                      UUID invitedByUserId, String email, LocalDate on, LocalDate expiresOn) {
        jdbc.update("""
            insert into um_invitation(invitation_id, workspace_id, role, token_hash, status,
                                      invited_by_user_id, issued_on, expires_on)
            values (?,?,?,?,'PENDING',?,?,?)
            """, invitationId, workspaceId, role.name(), tokenHash, invitedByUserId, on, expiresOn);
        jdbc.update("insert into um_invitation_recipient(invitation_id, email) values (?,?)",
            invitationId, email);
    }

    /**
     * Left join, so an invitation whose recipient row has already been dropped still comes back —
     * with a null email. The caller has to decide what that means; swapping to an inner join here
     * would turn "already used" into "unknown token", which is a different refusal and a worse one.
     */
    @Override
    public Optional<Invitation> byTokenHash(String tokenHash) {
        return jdbc.query("""
            select i.invitation_id, i.workspace_id, i.role, r.email
            from um_invitation i
            left join um_invitation_recipient r on r.invitation_id = i.invitation_id
            where i.token_hash = ?
            """, (rs, i) -> new Invitation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                Role.valueOf(rs.getString(3)), rs.getString(4)),
            tokenHash).stream().findFirst();
    }

    @Override
    public void accept(UUID invitationId, UUID acceptedByUserId) {
        jdbc.update(
            "update um_invitation set status = 'ACCEPTED', accepted_by_user_id = ? where invitation_id = ?",
            acceptedByUserId, invitationId);
        dropRecipient(invitationId);
    }

    @Override
    public void revoke(UUID invitationId) {
        jdbc.update("update um_invitation set status = 'REVOKED' where invitation_id = ?", invitationId);
        dropRecipient(invitationId);
    }

    private void dropRecipient(UUID invitationId) {
        jdbc.update("delete from um_invitation_recipient where invitation_id = ?", invitationId);
    }
}
