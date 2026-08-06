package pl.najem.um.domain.events;

import java.time.LocalDate;
import java.util.UUID;

public record InvitationRevoked(UUID workspaceId, UUID invitationId, LocalDate revokedOn)
    implements WorkspaceEvent {}
