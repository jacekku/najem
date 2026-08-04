package pl.najem.contracts.events;

import java.util.UUID;

/** Emitted by UserManagement so other modules can validate workspace existence. */
public record WorkspaceCreatedEvent(UUID workspaceId, String name) implements IntegrationEvent {
}
