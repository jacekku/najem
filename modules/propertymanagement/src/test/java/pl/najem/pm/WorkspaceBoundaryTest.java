package pl.najem.pm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workspace is the hard tenancy boundary, and this class of defect has proved able to grow while
 * assigned to someone: two new instances appeared between the finding being filed and this test
 * being written (najem-reviewer, seq 217). So the guard is the deliverable — fixing eleven call
 * sites without it just resets the count.
 *
 * <p>A source scan rather than a behavioural test on purpose. Behaviour can only cover the paths
 * somebody thought to write; the twelfth instance will be on a path nobody has thought of yet,
 * and this fails on it the moment it is typed.
 */
class WorkspaceBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java/pl/najem/pm");

    /**
     * {@code pm_process_due} is the one table with no workspace column, deliberately: it holds
     * timers keyed by (kind, subject_id) where the subject is an aggregate whose own workspace is
     * checked when the process manager loads it. Adding a workspace here would be a second copy
     * of a fact the aggregate already owns. Listed rather than pattern-matched so that exempting
     * a second table is a decision somebody makes, not a regex somebody widens.
     */
    private static final Set<String> TABLES_WITHOUT_A_WORKSPACE = Set.of("pm_process_due");

    private static final Pattern UPDATE = Pattern.compile("update\\s+(pm_\\w+)", Pattern.CASE_INSENSITIVE);

    /**
     * Every write to a projection row must name the workspace it is allowed to touch. Without it
     * a caller who knows a UUID modifies a row in an agency they have nothing to do with, the
     * update reports success, and per-workspace uniqueness means it never collides with the
     * correct row — so nothing later detects it.
     */
    @Test
    void everyProjectionWriteNamesItsWorkspace() throws IOException {
        var offenders = sqlStatements()
            .filter(sql -> sql.toLowerCase().startsWith("update pm_"))
            .filter(sql -> !exempt(sql))
            .filter(sql -> !sql.toLowerCase().contains("workspace_id = ?"))
            .toList();

        assertThat(offenders)
            .as("these writes can modify a row in another agency: add 'and workspace_id = ?'")
            .isEmpty();
    }

    /** Same rule for deletes — releasing a timer or a row in someone else's books. */
    @Test
    void everyProjectionDeleteNamesItsWorkspace() throws IOException {
        var offenders = sqlStatements()
            .filter(sql -> sql.toLowerCase().startsWith("delete from pm_"))
            .filter(sql -> !exempt(sql))
            .filter(sql -> !sql.toLowerCase().contains("workspace_id = ?"))
            .toList();

        assertThat(offenders).isEmpty();
    }

    /**
     * A read that forgets the predicate shows the wrong list — visible and self-correcting. It
     * still must not happen, and it is the same one-word omission as the write, so it is checked
     * with the same instrument rather than left to review.
     */
    @Test
    void everyProjectionReadNamesItsWorkspace() throws IOException {
        var offenders = sqlStatements()
            .filter(sql -> sql.toLowerCase().startsWith("select"))
            .filter(sql -> sql.toLowerCase().contains(" pm_"))
            .filter(sql -> !exempt(sql))
            .filter(sql -> !sql.toLowerCase().contains("workspace_id = ?"))
            .toList();

        assertThat(offenders).isEmpty();
    }

    /**
     * A write endpoint that takes no workspace cannot check one, whatever the service does. This
     * is the gap that let POST /api/pm/repairs/{id}/complete finish a repair in any agency.
     */
    @Test
    void everyWriteControllerTakesTheWorkspaceHeader() throws IOException {
        var offenders = javaSources(MAIN.resolve("adapter/rest"))
            .filter(path -> !path.getFileName().toString().equals("WorkspaceHeader.java"))
            .filter(path -> !path.getFileName().toString().equals("PmExceptionHandler.java"))
            .filter(path -> !path.getFileName().toString().equals("TestEndpointsEnabled.java"))
            // The process runner is opt-in test scaffolding and sweeps every workspace by design.
            .filter(path -> !path.getFileName().toString().equals("ProcessRunnerController.java"))
            .filter(WorkspaceBoundaryTest::hasWriteMapping)
            .filter(path -> !read(path).contains("@RequestHeader(WorkspaceHeader.NAME)"))
            .map(path -> path.getFileName().toString())
            .toList();

        assertThat(offenders)
            .as("a controller with a write mapping and no workspace header cannot check one")
            .isEmpty();
    }

    /** Nothing in PM may reintroduce a dev-workspace stand-in under any name. */
    @Test
    void nodevWorkspaceConstantComesBack() throws IOException {
        var offenders = javaSources(MAIN)
            .filter(path -> read(path).contains("00000000-0000-0000-0000-0000"))
            .map(path -> path.getFileName().toString())
            .toList();

        assertThat(offenders).isEmpty();
    }

    private static boolean exempt(String sql) {
        return TABLES_WITHOUT_A_WORKSPACE.stream().anyMatch(sql::contains);
    }

    private static boolean hasWriteMapping(Path path) {
        String source = read(path);
        return source.contains("@PostMapping") || source.contains("@PutMapping")
            || source.contains("@DeleteMapping") || source.contains("@PatchMapping");
    }

    /**
     * Pulls the SQL out of the source rather than out of a running query, so a statement that no
     * test happens to execute is still checked. Concatenated string literals are joined first —
     * a multi-line statement must not read as several fragments, or half of them would look
     * predicate-free and the other half would look like they name no table.
     */
    private static Stream<String> sqlStatements() throws IOException {
        var pattern = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        var text = javaSources(MAIN).map(WorkspaceBoundaryTest::read)
            .map(source -> source.replaceAll("\"\\s*\\+\\s*\"", ""))   // join concatenations
            .toList();
        return text.stream().flatMap(source -> {
            var matcher = pattern.matcher(source);
            return matcher.results().map(result -> result.group(1).replaceAll("\\s+", " ").trim());
        }).filter(sql -> UPDATE.matcher(sql).find() || sql.toLowerCase().contains(" pm_")
            || sql.toLowerCase().startsWith("delete from pm_"));
    }

    private static Stream<Path> javaSources(Path root) throws IOException {
        assertThat(Files.isDirectory(root))
            .as("%s not found — this test must fail loudly rather than scan nothing", root)
            .isTrue();
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(files).as("no sources found under %s", root).isNotEmpty();
        return files.stream();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
