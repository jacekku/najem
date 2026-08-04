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
 * <p>This guard's blast radius stops at this package. Ten write endpoints elsewhere still accept
 * the header with a DEV fallback (najem-build seq 154/155) — that is a separate, reported problem
 * and this test cannot see it.
 */
class NoDevHeaderTest {

    private static final String FORBIDDEN = "X-Workspace-Id";

    @Test
    void noSourceFileInTheWebPackageMentionsTheDevWorkspaceHeader() throws IOException {
        List<Path> offenders;
        try (Stream<Path> sources = Files.walk(webPackage())) {
            offenders = sources
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("NoDevHeaderTest.java"))
                .filter(NoDevHeaderTest::mentionsTheHeader)
                .toList();
        }

        assertThat(offenders)
            .as("%s must not appear in pl.najem.app.web — the UI resolves a workspace, "
                + "it does not assert one", FORBIDDEN)
            .isEmpty();
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

    private static boolean mentionsTheHeader(Path file) {
        try {
            return Files.readString(file).contains(FORBIDDEN);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }
}
