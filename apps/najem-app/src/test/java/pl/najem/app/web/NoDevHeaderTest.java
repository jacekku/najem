package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The UI must never set, read or forward {@code X-Workspace-Id}.
 *
 * <p>That header is a Phase 1 stand-in whose own javadoc calls it a stand-in for access control:
 * any caller can assert any workspace. A UI that authenticates a user and then re-asserts the
 * workspace as a header the callee trusts unconditionally is a tenancy boundary enforced by the
 * client — which is not a boundary. The web layer calls application services in process with an
 * explicitly resolved workspace instead (plan decision C, najem-build seq 156).
 *
 * <p>This guard's blast radius stops at this package, and deliberately carries no count of what
 * lies outside it: the writes it originally named have since been fixed and the reads have not,
 * so a number here goes stale silently and reads as current. Modules elsewhere still take the
 * workspace from a client-supplied header and check it against nothing — a separate, reported
 * problem (najem-build seq 272) that this test cannot see.
 */
class NoDevHeaderTest {

    private static final String FORBIDDEN = "X-Workspace-Id";

    /**
     * {@code WorkspaceHeaderInterceptor} is the single exemption, and the distinction is the whole
     * point rather than a convenience: it names the header in order to <em>reject</em> it, never to
     * consume it as truth. Everything else in this package must not know the header exists.
     *
     * <p>This guard caught that interceptor on its first full build — which is the guard working.
     * The fix was to name one file with a reason, not to soften the rule: an exemption list that
     * grows by "someone needed it" is the convention this test replaced.
     */
    @Test
    void onlyTheInterceptorThatRejectsTheDevHeaderMayNameIt() throws IOException {
        assertThat(sourcesContaining(FORBIDDEN, "NoDevHeaderTest.java", "WorkspaceHeaderInterceptor.java"))
            .as("%s must not appear in pl.najem.app.web — the UI resolves a workspace, "
                + "it does not assert one", FORBIDDEN)
            .isEmpty();
    }

    /**
     * A record's canonical constructor is public, so {@code new WebWorkspace(...)} compiles in any
     * class in this package — including every controller. Nothing exploits that today, but the
     * type's whole value is that holding one proves a membership check happened, and a forged one
     * is indistinguishable from a resolved one. This is the guard that makes the javadoc true.
     */
    @Test
    void onlyTheResolverConstructsAWorkspace() throws IOException {
        assertThat(sourcesContaining("new WebWorkspace(",
            "WebWorkspaceResolver.java", "NoDevHeaderTest.java"))
            .as("only WebWorkspaceResolver may construct a WebWorkspace — one built anywhere else "
                + "is an unchecked workspace wearing a checked one's type")
            .isEmpty();
    }

    private static List<Path> sourcesContaining(String needle, String... exempt) throws IOException {
        var exemptions = List.of(exempt);
        try (Stream<Path> sources = Files.walk(webPackage())) {
            return sources
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !exemptions.contains(p.getFileName().toString()))
                .filter(p -> contains(p, needle))
                .toList();
        }
    }

    /** Fails loudly rather than passing vacuously if the sources move. */
    private static Path webPackage() {
        Path main = Path.of("src/main/java/pl/najem/app/web");
        Path fromRepoRoot = Path.of("apps/najem-app").resolve(main);
        Path resolved = Files.isDirectory(main) ? main : fromRepoRoot;
        assertThat(Files.isDirectory(resolved))
            .as("cannot find the web package to scan — this guard would pass without checking "
                + "anything, which is worse than not having it")
            .isTrue();
        return resolved;
    }

    private static boolean contains(Path file, String needle) {
        try {
            return Files.readString(file).contains(needle);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }
}
