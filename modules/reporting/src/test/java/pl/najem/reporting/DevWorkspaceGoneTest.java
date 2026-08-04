package pl.najem.reporting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-Keycloak {@code DEV_WORKSPACE_ID} fallback is deleted from this module, and this is what
 * stops it coming back. Modelled on @najem-pm's {@code nodevWorkspaceConstantComesBack}, which was
 * the only module that had already done it (najem-build seq 248).
 *
 * <p><b>Deleted rather than guarded, because the strongest form of unreachable is absent.</b>
 * {@code resolve(header)} returning a constant for a missing header meant that omitting the header
 * served workspace {@code …0001}'s data to an unauthenticated caller. Reporting's timelines are one
 * tenancy's entire story, so that was the most complete cross-tenant read in the tree; contacts'
 * were personal data and an erasure worklist.
 *
 * <p>The justification this replaces was mine, and it is the one rule 7 answers directly: <em>"an
 * absent header resolving to the dev workspace is the only thing that keeps it usable before an
 * issuer is configured."</em> Rule 7 says that where a value is genuinely absent the application
 * refuses — <b>being unusable without configuration is the intended outcome, not a cost to be
 * engineered around.</b> Every read endpoint here now requires the header and returns 400 without
 * it.
 *
 * <p>Scoped to this module because four other modules would be red today and a test that ships red
 * teaches people to ignore it. @najem-coordinator's plan (seq 251) folds these into one tree-wide
 * scan once every module has one; this is the honest intermediate.
 */
class DevWorkspaceGoneTest {

    private static final Path MAIN = Path.of("src/main/java/pl/najem/reporting");

    @Test
    void noDevWorkspaceConstantComesBack() throws IOException {
        var offenders = sourceLines()
            .filter(line -> line.contains("DEV_WORKSPACE_ID")
                || line.contains("00000000-0000-0000-0000-000000000001"))
            .toList();

        assertThat(offenders)
            .as("a default workspace serves one agency's data to a caller who named none: "
                + "require the header and let the request fail")
            .isEmpty();
    }

    /**
     * The half that would let the first be satisfied dishonestly. Re-declaring the header as
     * {@code required = false} reintroduces exactly the same hole with a fresh constant, or with a
     * null that some query treats as "all workspaces" — and it would pass a scan looking only for
     * the old literal.
     */
    @Test
    void noWorkspaceHeaderIsOptional() throws IOException {
        var offenders = sourceLines()
            .filter(line -> line.contains("X-Workspace-Id") && line.contains("required = false"))
            .toList();

        assertThat(offenders)
            .as("an optional workspace header is a default workspace wearing different clothes: "
                + "whatever the code does with the null, the caller never named an agency")
            .isEmpty();
    }

    private static Stream<String> sourceLines() throws IOException {
        List<Path> files;
        try (var walk = Files.walk(MAIN)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        return files.stream().flatMap(file -> {
            try {
                return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("*") && !line.startsWith("//"))
                    .map(line -> file.getFileName() + ": " + line);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + file, e);
            }
        });
    }
}
