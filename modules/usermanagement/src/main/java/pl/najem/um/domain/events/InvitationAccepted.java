package pl.najem.um.domain.events;

import java.time.LocalDate;
import java.util.UUID;

public record InvitationAccepted(UUID workspaceId, UUID invitationId, UUID userId, LocalDate acceptedOn)
    implements WorkspaceEvent {}
