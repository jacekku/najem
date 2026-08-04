package pl.najem.app.web;

import pl.najem.um.domain.Role;

import java.util.UUID;

/**
 * The workspace a request acts in, already resolved and already checked.
 *
 * <p>Controllers receive this and pass {@link #workspaceId()} explicitly into application services.
 * Templates receive data derived from it and never the object's provenance — a screen must not be
 * able to ask "which workspace am I?", only to be told.
 */
public record WebWorkspace(UUID workspaceId, UUID userId, UUID subject, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
