package pl.najem.um.domain.events;

import java.time.LocalDate;
import java.util.UUID;

public record MemberRemoved(UUID workspaceId, UUID userId, LocalDate removedOn)
    implements WorkspaceEvent {}
