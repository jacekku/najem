package pl.najem.um.application;

import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link InvitationRepository}. Stands in for {@code um_invitation} and its recipient
 * lookaside.
 *
 * <p>The two tables are kept separate here, as they are in the schema, so that dropping a recipient
 * leaves an invitation which still resolves by token and reports a null email. Collapsing them into
 * one map would make {@code byTokenHash} return empty after acceptance, which is the wrong refusal
 * — "unknown token" rather than "already used" — and the real left join does not do that.
 */
public class InMemoryInvitations implements InvitationRepository {

    private record Row(UUID invitationId, UUID workspaceId, Role role, String tokenHash) {}

    private final Map<UUID, Row> invitations = new LinkedHashMap<>();
    private final Map<UUID, String> recipients = new LinkedHashMap<>();

    @Override
    public void issue(UUID invitationId, UUID workspaceId, Role role, String tokenHash,
                      UUID invitedByUserId, String email, LocalDate on, LocalDate expiresOn) {
        // um_invitation.token_hash is unique.
        invitations.values().stream().filter(r -> r.tokenHash().equals(tokenHash)).findAny()
            .ifPresent(r -> { throw new IllegalStateException("token hash already issued"); });
        invitations.put(invitationId, new Row(invitationId, workspaceId, role, tokenHash));
        recipients.put(invitationId, email);
    }

    @Override
    public Optional<Invitation> byTokenHash(String tokenHash) {
        return invitations.values().stream()
            .filter(r -> r.tokenHash().equals(tokenHash))
            .map(r -> new Invitation(r.invitationId(), r.workspaceId(), r.role(),
                recipients.get(r.invitationId())))
            .findFirst();
    }

    @Override
    public void accept(UUID invitationId, UUID acceptedByUserId) {
        recipients.remove(invitationId);
    }

    @Override
    public void revoke(UUID invitationId) {
        recipients.remove(invitationId);
    }
}
