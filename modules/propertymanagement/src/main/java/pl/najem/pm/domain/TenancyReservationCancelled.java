package pl.najem.pm.domain;

import java.util.UUID;

public record TenancyReservationCancelled(UUID workspaceId, UUID tenancyId, String reason) {
}
