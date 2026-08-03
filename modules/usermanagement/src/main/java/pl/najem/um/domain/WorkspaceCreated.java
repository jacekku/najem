package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record WorkspaceCreated(UUID workspaceId, String name, LocalDate createdOn) {}
