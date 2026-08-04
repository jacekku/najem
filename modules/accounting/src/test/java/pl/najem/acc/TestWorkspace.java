package pl.najem.acc;

import java.util.UUID;

/**
 * A workspace id for tests to write rows under.
 *
 * <p>This is where the deleted production constant went. It lives in test sources deliberately: a
 * fixture needs <em>some</em> workspace, and there
 * is no harm in a known one, but nothing on the main source path may name a workspace on a caller's
 * behalf. {@code DevWorkspaceGoneTest} scans {@code src/main} and would fail if this moved there.
 */
public final class TestWorkspace {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private TestWorkspace() {
    }
}
