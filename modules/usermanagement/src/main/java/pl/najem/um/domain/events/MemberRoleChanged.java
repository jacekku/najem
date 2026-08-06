package pl.najem.um.domain.events;

import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

public record MemberRoleChanged(UUID workspaceId, UUID userId, Role role, LocalDate changedOn)
    implements WorkspaceEvent {}
