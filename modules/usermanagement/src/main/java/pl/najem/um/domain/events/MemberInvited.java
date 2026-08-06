package pl.najem.um.domain.events;

import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

/**
 * No email, no name: the invitee's address lives in the um_invitation_recipient lookaside (decision
 * D3) and is deleted on accept, revoke or expiry. An invitee is not a contact, so it does not belong
 * in the Contacts PII table either (settled with najem-contacts, najem-build seq 47).
 */
public record MemberInvited(UUID workspaceId, UUID invitationId, Role role,
                            UUID invitedByUserId, LocalDate issuedOn, LocalDate expiresOn)
    implements WorkspaceEvent {}
