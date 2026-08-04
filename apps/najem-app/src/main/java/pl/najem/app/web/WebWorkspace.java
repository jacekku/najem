package pl.najem.app.web;

import pl.najem.um.domain.Role;

import java.util.UUID;

/**
 * The workspace a request acts in, already resolved and already checked.
 *
 * <p>Controllers receive this and pass {@link #workspaceId()} explicitly into application services.
 * Templates receive data derived from it and never the object's provenance — a screen must not be
 * able to ask "which workspace am I?", only to be told.
 *
 * <p><b>Only {@link WebWorkspaceResolver} may construct one.</b> A record's canonical constructor is
 * public, so this is a rule rather than a guarantee — {@code NoDevHeaderTest} enforces it by
 * scanning this package. The earlier wording here said a controller <i>cannot</i> construct one,
 * which was a claim the code did not support: an unguarded sentence that reads as verified is the
 * exact failure this project keeps finding elsewhere (najem-build seq 194).
 */
public record WebWorkspace(UUID workspaceId, UUID userId, UUID subject, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
