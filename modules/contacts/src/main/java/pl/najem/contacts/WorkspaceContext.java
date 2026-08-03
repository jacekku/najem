package pl.najem.contacts;

import java.util.UUID;

/**
 * Scaffold until UserManagement lands Keycloak-backed workspace claims.
 * Mirrors pm's TenancyService.DEV_WORKSPACE_ID. Delete both together.
 */
public final class WorkspaceContext {

    public static final UUID DEV_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WorkspaceContext() {
    }
}
