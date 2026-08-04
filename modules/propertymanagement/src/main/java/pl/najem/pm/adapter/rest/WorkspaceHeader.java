package pl.najem.pm.adapter.rest;

/**
 * The name of the header that carries the workspace. Nothing more.
 *
 * <p>This used to resolve a missing header to a DEV_WORKSPACE_ID constant. Under rule 7 a
 * convenience default may not be reachable from production, and PM's only caller was a WRITE —
 * {@code POST /api/pm/properties} — so an omitted header created a property in books the caller
 * never named. The fallback is deleted rather than guarded: the strongest form of unreachable
 * is absent, and PM has no read endpoint that wanted one.
 */
public final class WorkspaceHeader {

    public static final String NAME = "X-Workspace-Id";

    private WorkspaceHeader() {
    }
}
