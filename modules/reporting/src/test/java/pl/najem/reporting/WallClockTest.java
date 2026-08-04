package pl.najem.reporting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reporting's one, assigned with PM's three (najem-coordinator, seq 251). {@code orToday} decided
 * the as-of date for the tenancy timeline, the unit timeline, the occupancy counts and the arrears
 * board — four read surfaces, one wall clock, and no test could move any of them.
 *
 * <p>The scan matters more here than the single edit did. Reporting is all queries and every one
 * of them takes a date, so this is the module where the next {@code now()} is most likely to be
 * typed. Matching on the injected clock being PRESENT rather than on forbidden spellings being
 * absent is najem-reviewer's seq 252 correction: {@code LocalDate.now(ZoneId.of("Europe/Warsaw"))}
 * reads as having learned the lesson from {@code c2c7f0a} and is still a date no test can move.
 *
 * <p>Kept as a sibling of PM's rather than shared, because a scan that walks its own module is the
 * thing that fails when that module regresses. @najem-coordinator has said they will fold the
 * per-module scans into one tree-wide check once all five exist.
 */
class WallClockTest {

    private static final Path MAIN = Path.of("src/main/java/pl/najem/reporting");

    /** Any {@code .now(...)} at all, so the argument can be inspected rather than the name. */
    private static final Pattern NOW = Pattern.compile("\\.now\\(([^)]*)\\)");

    /** Any Clock factory — {@code system}, {@code systemUTC}, {@code tickMinutes}, all of them. */
    private static final Pattern MINTS_A_CLOCK = Pattern.compile("\\bClock\\.\\w+\\(");

    /**
     * The clock must be the injected one. {@code now()} is a wall clock, and so is
     * {@code now(ZoneId.of("Europe/Warsaw"))} — naming the right zone changes the timezone bug and
     * not the untestability, which was the finding.
     */
    @Test
    void everyDateInProductionComesFromTheInjectedClock() throws IOException {
        var offenders = sourceLines()
            .filter(line -> NOW.matcher(line).results()
                .anyMatch(hit -> !hit.group(1).strip().equals("clock")))
            .toList();

        assertThat(offenders)
            .as("call now(clock): a date the application invents here cannot be driven by any "
                + "test, whichever zone it names")
            .isEmpty();
    }

    /**
     * The other half, and the one that lets the first be satisfied dishonestly: a field
     * {@code private final Clock clock = Clock.system(WARSAW);} passes every check above while
     * restoring the defect exactly. A guard satisfiable by moving the defect one line up is not a
     * guard (najem-coordinator, seq 251).
     *
     * <p>No exemption is needed here because PM is a module, not the composition root. The one
     * legitimate {@code Clock.system(WARSAW)} in the tree lives in {@code apps/najem-app}, where
     * minting it is the job — which is why this scan is per-module and points at PM only.
     */
    @Test
    void nothingInProductionMintsItsOwnClock() throws IOException {
        var offenders = sourceLines()
            .filter(line -> MINTS_A_CLOCK.matcher(line).find())
            .toList();

        assertThat(offenders)
            .as("take the Clock as a constructor parameter: a convenience default belongs in the "
                + "test that chose it, not in the code production runs")
            .isEmpty();
    }

    /**
     * Both tests above assert a list is empty, so a scan that reads nothing — a moved package, a
     * regex that stopped matching, a walk over a path that no longer exists — passes them both in
     * silence. najem-reviewer, seq 249: *"a source scan that walks a path that silently doesn't
     * exist passes vacuously."* This is the assertion that makes their green mean something.
     */
    @Test
    void thescanReadsPmsSourceAndCanSeeAClockCall() throws IOException {
        var lines = sourceLines().toList();

        assertThat(lines).as("reporting's source cannot have shrunk to nothing").hasSizeGreaterThan(400);
        assertThat(lines.stream().filter(line -> line.contains(".now(clock)")).toList())
            .as("the pattern must be able to see the fixed form, or it sees nothing at all")
            .hasSizeGreaterThanOrEqualTo(1);
    }

    private static Stream<String> sourceLines() throws IOException {
        assertThat(Files.isDirectory(MAIN))
            .as("%s not found — this test must fail loudly rather than scan nothing", MAIN)
            .isTrue();
        List<Path> files;
        try (var walk = Files.walk(MAIN)) {
            files = walk.filter(path -> path.toString().endsWith(".java")).toList();
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
