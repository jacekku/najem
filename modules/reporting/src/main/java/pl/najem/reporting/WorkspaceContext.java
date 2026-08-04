package pl.najem.reporting;

import java.util.UUID;

/**
 * The pre-Keycloak seam, identical to the one in pm, accounting and contacts — the fifth copy, and
 * it dies with the others when {@code WorkspaceAccess.roleIn(subject, workspaceId)} replaces them.
 * <p>
 * Reporting reads nothing without a workspace, so an absent header resolving to the dev workspace
 * is the only thing that keeps it usable before an issuer is configured.
 */
public final class WorkspaceContext {

    public static final UUID DEV_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WorkspaceContext() {
    }

    public static UUID resolve(UUID fromHeader) {
        return fromHeader == null ? DEV_WORKSPACE_ID : fromHeader;
    }
}
