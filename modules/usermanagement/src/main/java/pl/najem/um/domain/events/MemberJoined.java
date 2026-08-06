package pl.najem.um.domain.events;

import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Somebody became a member of an agency.
 *
 * <p>Added when {@code um_membership} became derived, because until then <b>the founding ADMIN was
 * a member no event ever recorded</b>. {@code WorkspaceService.create} wrote the row and appended
 * only {@link WorkspaceCreated}, so replaying any workspace stream produced an agency with no
 * members at all — including the one person who could invite anybody into it.
 *
 * <p>Emitted on creation and on invitation acceptance. {@link InvitationAccepted} stays, and stays
 * separate: it says an invitation was used, which is an audit fact about a token, while this says
 * somebody holds a role, which is the membership fact. One event carrying both would leave the
 * founder — who accepts no invitation — with nothing to record.
 */
public record MemberJoined(UUID workspaceId, UUID userId, Role role, LocalDate joinedOn)
    implements WorkspaceEvent {}
