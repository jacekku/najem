package pl.najem.pm.adapter.rest;

import java.util.UUID;

/**
 * Phase 1 stand-in for access control. UserManagement owns membership resolution; when it
 * lands, only this class changes — the application services already take an explicit
 * workspaceId. Same single-seam shape as modules/contacts' WorkspaceContext.
 */
public final class WorkspaceHeader {

    public static final String NAME = "X-Workspace-Id";

    /** Shared with contacts and usermanagement; these constants die together. */
    public static final UUID DEV_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WorkspaceHeader() {
    }

    public static UUID resolve(UUID header) {
        return header != null ? header : DEV_WORKSPACE_ID;
    }
}
