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
     *
     * <p>Reads as well as writes, which is not where this started. The rule was written for write
     * mappings and it let {@code GET /api/pm/attention/tenancies/&#123;id&#125;/warnings} through —
     * no header, no guard, any tenancy id in any agency, disclosing another manager's statutory
     * position on their tenancy. A read that leaks is quieter than a write that corrupts and it is
     * the same omission, so it is the same rule: no endpoint in PM without a workspace.
     *
     * <p>Per mapping, not per file (najem-reviewer, seq 243). A file-wide {@code contains} passes
     * a controller the moment one of its methods takes the header, so the twelfth endpoint added
     * to {@code TenancyController} taking none at all would have been green — and the two
     * instances this class grew while under assignment were both born inside controllers that
     * already took the header somewhere else in the file. The earlier version of this test would
     * not have caught the thing it was written for.
     *
     * <p>What the parameter <i>is</i> has changed and the rule has not. It used to be
     * {@code @RequestHeader(WorkspaceHeader.NAME)} — a workspace the caller named, checked
     * afterwards by an interceptor — and is now {@code @ActingWorkspace}, a workspace derived from
     * the caller that nobody can name. The endpoint still has to accept one, because an endpoint
     * that takes no workspace still cannot scope anything.
     */
    @Test
    void everyMappingTakesTheWorkspaceItActsIn() throws IOException {
        var offenders = mappings(ANY_MAPPING)
            .filter(mapping -> !mapping.source().contains("@ActingWorkspace"))
            .map(Mapping::name)
            .toList();

        assertThat(offenders)
            .as("a mapping handed no workspace cannot check one")
            .isEmpty();
    }

    /**
     * The header is only the input; {@link pl.najem.pm.application.WorkspaceGuard} is what refuses.
     *
     * <p>This is the assertion that actually defends PM, and it did not exist until seq 243 pointed
     * out why: the {@code workspace_id = ?} predicates the tests above check are bound to the
     * aggregate's own workspace, so they are satisfied by construction and cannot exclude anything.
     * That derivation is the right design — a caller cannot assert a workspace it does not own —
     * but it means the SQL scan passes for a reason unrelated to safety. Delete a {@code guard.}
     * line and every other test in this class stays green while the endpoint stands wide open.
     */
    @Test
    void everyWriteMappingReachesTheGuard() throws IOException {
        var offenders = writeMappings()
            .filter(mapping -> !GUARD_NOT_APPLICABLE.contains(mapping.method()))
            .filter(mapping -> !mapping.reachesGuard())
            .map(Mapping::name)
            .toList();

        assertThat(offenders)
            .as("these endpoints accept a workspace and never check it against the subject")
            .isEmpty();
    }

    /**
     * The two tests above are assertions that a list is empty, so a parser that returns nothing
     * passes them both — and one did: skipping the annotation's braces was wrong first time and
     * every mapping came back named {@code ?}. This is what makes the silence meaningful.
     */
    @Test
    void thescanFindsEveryWriteMappingAndNamesIt() throws IOException {
        var mappings = writeMappings().toList();

        assertThat(mappings).as("PM's write surface cannot have shrunk to nothing")
            .hasSizeGreaterThanOrEqualTo(18);
        assertThat(mappings).as("an unparsed method is not a checked method")
            .extracting(Mapping::method).doesNotContain("?");
        assertThat(mappings).extracting(Mapping::name)
            .contains("RepairController.complete", "TenancyController.end");
    }

    /**
     * A create owns nothing yet: {@code createProperty} takes the workspace and stamps it on a
     * property that did not exist a moment ago, so there is no prior row to check it against. By
     * name rather than by rule, for the same reason as the table exemption above.
     */
    private static final Set<String> GUARD_NOT_APPLICABLE = Set.of("createProperty");

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

    private static final Pattern WRITE_MAPPING =
        Pattern.compile("@(?:Post|Put|Delete|Patch)Mapping");

    /** Reads included: the header rule is about every endpoint, the guard rule about writes. */
    private static final Pattern ANY_MAPPING =
        Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping");

    private static final Pattern METHOD_NAME = Pattern.compile("(\\w+)\\s*\\(");

    private static final Pattern PRIVATE_METHOD =
        Pattern.compile("private\\s+(?:static\\s+)?[\\w.<>,\\[\\]\\s]+?\\s(\\w+)\\s*\\(");

    /**
     * One write endpoint: the method as written, plus the names of every guard-calling helper in
     * its file, so a check made through {@code RepairController.requireAsset} counts as made.
     */
    private record Mapping(String file, String method, String source, Set<String> guardHelpers) {

        String name() {
            return file + "." + method;
        }

        boolean reachesGuard() {
            return source.contains("guard.")
                || guardHelpers.stream().anyMatch(helper -> source.contains(helper + "("));
        }
    }

    private static Stream<Mapping> writeMappings() throws IOException {
        return mappings(WRITE_MAPPING);
    }

    private static Stream<Mapping> mappings(Pattern kind) throws IOException {
        return javaSources(MAIN.resolve("adapter/rest"))
            // Opt-in test scaffolding that sweeps every workspace by design — see
            // TestEndpointsEnabled; it does not exist unless a property turns it on.
            .filter(path -> !path.getFileName().toString().equals("ProcessRunnerController.java"))
            .flatMap(path -> mappingsIn(path, kind));
    }

    private static Stream<Mapping> mappingsIn(Path path, Pattern kind) {
        String source = read(path);
        String file = path.getFileName().toString().replace(".java", "");
        Set<String> helpers = guardCallingHelpers(source);
        return kind.matcher(source).results()
            .map(hit -> methodAt(source, hit.start()))
            .map(method -> new Mapping(file, nameOf(method), method, helpers));
    }

    private static Set<String> guardCallingHelpers(String source) {
        return PRIVATE_METHOD.matcher(source).results()
            .filter(hit -> methodAt(source, hit.start()).contains("guard."))
            .map(hit -> hit.group(1))
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * The declaration at {@code from} through the closing brace of its body, by brace depth rather
     * than by slicing at the next annotation — otherwise the last method in a file swallows every
     * private helper below it and inherits their guard calls.
     */
    private static String methodAt(String source, int from) {
        int open = source.indexOf('{', skipAnnotationArguments(source, from));
        if (open < 0) {
            return source.substring(from);
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(from, i + 1);
            }
        }
        return source.substring(from);
    }

    /**
     * A path variable puts braces inside the annotation — {@code @PostMapping("/{repairId}/complete")}
     * — and they close, so a naive scan for the body finds {@code {repairId\}} and reads a method
     * one word long. Every mapping then looks header-less and guard-less at once, which is how
     * this was caught: an all-red scan is a broken scan, not twenty new findings.
     */
    private static int skipAnnotationArguments(String source, int from) {
        int paren = source.indexOf('(', from);
        int brace = source.indexOf('{', from);
        if (paren < 0 || (brace >= 0 && brace < paren)) {
            return from;
        }
        int depth = 0;
        for (int i = paren; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return from;
    }

    private static String nameOf(String method) {
        var matcher = METHOD_NAME.matcher(method);
        String last = "?";
        while (matcher.find()) {
            String candidate = matcher.group(1);
            // Skip the annotation and modifiers; the declared name is the one before the body.
            if (!candidate.endsWith("Mapping") && !candidate.equals("value")) {
                last = candidate;
                break;
            }
        }
        return last;
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
