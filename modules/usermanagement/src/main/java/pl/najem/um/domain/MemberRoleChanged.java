package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record MemberRoleChanged(UUID workspaceId, UUID userId, Role role, LocalDate changedOn) {}
