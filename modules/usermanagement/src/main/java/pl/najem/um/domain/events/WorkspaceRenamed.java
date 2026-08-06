package pl.najem.um.domain.events;

import java.time.LocalDate;
import java.util.UUID;

public record WorkspaceRenamed(UUID workspaceId, String name, LocalDate renamedOn)
    implements WorkspaceEvent {}
