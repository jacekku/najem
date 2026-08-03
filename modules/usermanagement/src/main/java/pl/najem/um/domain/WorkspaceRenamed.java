package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record WorkspaceRenamed(UUID workspaceId, String name, LocalDate renamedOn) {}
