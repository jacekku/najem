package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only {@link WebWorkspaceResolver} may construct a {@link WebWorkspace}.
 *
 * <p>This class used to also forbid {@code X-Workspace-Id} anywhere in this package, and noted with
 * some regret that its blast radius stopped here while "modules elsewhere still take the workspace
 * from a client-supplied header and check it against nothing". That rule now applies to the whole
 * repository and lives in {@code WorkspaceHeaderGoneTest}, so it is gone from here rather than
 * duplicated — two guards over one rule disagree eventually, and the weaker one is the one people
 * read.
 */
class NoDevHeaderTest {

    private static final String FORBIDDEN = "X-Workspace-Id";

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
