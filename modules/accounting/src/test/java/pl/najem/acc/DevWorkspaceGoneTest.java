package pl.najem.acc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-Keycloak {@code DEV_WORKSPACE_ID} fallback is deleted from accounting, and this is what
 * stops it coming back. Modelled on @najem-pm's original and @najem-coordinator's copies in
 * reporting and contacts (najem-build seq 248, `aace41d`).
 *
 * <p><b>Deleted rather than guarded: the strongest form of unreachable is absent.</b> Four read
 * endpoints here resolved a missing header to workspace {@code …0001} — the arrears board, the
 * suspense queue, the reconciliation suggestions and the warnings list. That is one agency's
 * portfolio with its arrears beside it, its unmatched money, and what its manager has not yet read,
 * served to a caller who identified nothing.
 *
 * <p>The justification was mine and it was wrong on its own terms: I argued a read with no header
 * shows empty data and the caller notices. It did not show empty data. It showed somebody's.
 */
class DevWorkspaceGoneTest {

    private static final Path MAIN = Path.of("src/main/java/pl/najem/acc");

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
     * The half that would let the first be satisfied dishonestly. Re-declaring the header optional
     * reintroduces the same hole with a fresh constant, or with a null some query reads as "all
     * workspaces" — and it would pass a scan looking only for the old literal.
     *
     * <p>Matched against the whole file with whitespace removed rather than line by line, because
     * @najem-reviewer's seq 271 point applies here: {@code required = false}, {@code required=false}
     * and an annotation split across two lines are the same defect in three spellings, and a
     * line-oriented scan sees only the first.
     */
    @Test
    void noWorkspaceHeaderIsOptional() throws IOException {
        var offenders = sourceFiles().stream()
            .filter(file -> ANNOTATION.matcher(squashed(file)).find())
            .map(file -> file.getFileName().toString())
            .toList();

        assertThat(offenders)
            .as("an optional workspace header is a default workspace wearing different clothes: "
                + "whatever the code does with the null, the caller never named an agency")
            .isEmpty();
    }

    /** {@code @RequestHeader} naming the workspace and declaring itself optional, in any spelling. */
    private static final Pattern ANNOTATION = Pattern.compile(
        "@RequestHeader\\([^)]*X-Workspace-Id[^)]*required=false[^)]*\\)"
            + "|@RequestHeader\\([^)]*required=false[^)]*X-Workspace-Id[^)]*\\)");

    private static String squashed(Path file) {
        try {
            return Files.readString(file).replaceAll("\\s+", "");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    private static List<Path> sourceFiles() throws IOException {
        try (var walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static Stream<String> sourceLines() throws IOException {
        return sourceFiles().stream().flatMap(file -> {
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
