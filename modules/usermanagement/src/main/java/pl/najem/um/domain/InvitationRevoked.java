package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record InvitationRevoked(UUID workspaceId, UUID invitationId, LocalDate revokedOn) {}
