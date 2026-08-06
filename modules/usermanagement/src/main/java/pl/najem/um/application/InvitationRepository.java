package pl.najem.um.application;

import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Invitations and the addresses they were sent to.
 *
 * <p><b>{@code Repository}, not {@code Projection}</b>, and the asymmetry with
 * {@link MembershipProjection} beside it is deliberate rather than an oversight. Most of this table
 * <em>is</em> derivable — role, status and expiry all come off the {@code Workspace} stream. Two
 * columns are not: {@code token_hash} and the recipient's email appear in no event, by decision.
 * D4 keeps the token out of anything replayable, and D3 keeps the invitee's address out of an event
 * payload because an invitee is not a contact.
 *
 * <p>So {@code accept} can only start here: nothing else in the system can turn a token into an
 * invitation. Drop this table and those two facts are gone. That makes it the record.
 */
public interface InvitationRepository {

    /** What a token identifies. The aggregate decides whether it may still be used. */
    record Invitation(UUID invitationId, UUID workspaceId, Role role, String email) {}

    void issue(UUID invitationId, UUID workspaceId, Role role, String tokenHash,
               UUID invitedByUserId, String email, LocalDate on, LocalDate expiresOn);

    Optional<Invitation> byTokenHash(String tokenHash);

    /**
     * Marks the invitation used and drops the recipient row.
     *
     * <p>The address is deleted rather than retained: it was collected to deliver one invitation and
     * has no purpose afterwards (D3).
     */
    void accept(UUID invitationId, UUID acceptedByUserId);

    /** Marks it revoked and drops the recipient row, for the same reason. */
    void revoke(UUID invitationId);
}
