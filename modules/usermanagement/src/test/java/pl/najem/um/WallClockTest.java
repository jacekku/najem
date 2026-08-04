package pl.najem.um;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Services take a Clock so a test can drive the date, and then the controller above them called
 * {@code LocalDate.now()} and threw that away (najem-reviewer, seq 238). Seven of them in this
 * module — the date an invitation is issued on, the date it is revoked, the date a membership
 * changes — so the domain's dates were driveable and the application's were not.
 *
 * <p>What it cost is sharpest in {@code InvitationService}: it refuses an invitation that expires
 * before it is issued, and refuses one accepted after expiry. Both boundaries were decided by a
 * wall clock no test could move, so neither had ever been observed to fail at the boundary it
 * guards — only at dates handed in directly, one layer below where production gets them.
 *
 * <p>A source scan rather than a ruling, because "controllers should take a Clock" is the shape of
 * instruction that has leaked within the hour every time it has been given here. This fails the
 * moment the next one is typed, which a ruling cannot.
 */
class WallClockTest {

    private static final Path MAIN = Path.of("src/main/java/pl/najem/um");

    /**
     * {@code LocalDate.now(clock)} is fine and is the point; the bare no-argument call is not.
     * Matching on the absence of an argument rather than on the method name keeps the fixed form
     * from tripping its own guard.
     */
    @Test
    void nothingInProductionReadsTheWallClock() throws IOException {
        var offenders = sourceLines()
            .filter(line -> line.contains("LocalDate.now()")
                || line.contains("LocalDateTime.now()")
                || line.contains("Instant.now()"))
            .toList();

        assertThat(offenders)
            .as("inject the Clock bean and call now(clock): a date the application invents here "
                + "cannot be driven by any test, and is whatever timezone the container started in")
            .isEmpty();
    }

    /**
     * The other half, and the one that would let the first be satisfied dishonestly: a class may
     * not mint its own Clock either. {@code Clock.systemDefaultZone()} in production source is
     * rule 7 inverted — where the value is absent it manufactures one and carries on, instead of
     * refusing to start. It also silently picks up the container's timezone, which is the defect
     * PlatformConfig's bean exists to prevent (c2c7f0a).
     */
    @Test
    void nothingInProductionMintsItsOwnClock() throws IOException {
        var offenders = sourceLines()
            .filter(line -> line.contains("Clock.systemDefaultZone()") || line.contains("Clock.systemUTC()"))
            .toList();

        assertThat(offenders)
            .as("take the Clock as a constructor parameter: a convenience default belongs in the "
                + "test that wants it, where somebody chose it, not in the code production runs")
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
