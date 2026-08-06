package pl.najem.app.web.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No production code anywhere in this repository may read {@code X-Workspace-Id}.
 *
 * <p>The header let any caller assert which agency they were acting in. It was guarded — an
 * interceptor in the composition root checked the caller belonged to the workspace they named, and
 * that check was correct — but a guarded claim is still a claim, and every new endpoint had to
 * remember to be covered by it. The workspace is now derived from the caller, so there is nothing
 * to assert and nothing to guard.
 *
 * <p><b>Repository-wide on purpose.</b> The rule this replaces could only see
 * {@code pl.najem.app.web}, and its own javadoc recorded the consequence: "modules elsewhere still
 * take the workspace from a client-supplied header and check it against nothing". The 64 parameters
 * that did so are gone, and the guard that let them survive is what let them survive quietly.
 */
class WorkspaceHeaderGoneTest {

    private static final String FORBIDDEN = "X-Workspace-Id";

    /**
     * What it takes to actually consume the header. Naming it is not the offence — several classes
     * name it to explain where it went, and this test's first draft failed on its own javadoc.
     * <b>Reading it is the offence</b>, so that is what is looked for: the header's name together
     * with something that could fetch it.
     *
     * <p>An earlier version banned the name outright and needed three exemptions on day one. An
     * exemption list is a rule that gets edited every time it fires, and a rule edited that often
     * stops being believed — the guard this one replaces said exactly that about itself.
     */
    private static final List<String> READS_A_HEADER = List.of("getHeader", "RequestHeader");

    /**
     * The one sanctioned reader, gated on a property no packaged configuration sets — asserted
     * separately below, because a gate nobody checks is a comment.
     */
    private static final Set<String> EXEMPT = Set.of("HeaderWorkspaceArgumentResolver.java");

    @Test
    void noProductionSourceReadsTheWorkspaceHeader() throws IOException {
        assertThat(productionSourcesReading(FORBIDDEN))
            .as("nothing in production code may read %s — a request cannot name a workspace, "
                + "it can only be one", FORBIDDEN)
            .isEmpty();
    }

    /**
     * The header resolver exists behind a property, and a property is only a guard while nothing
     * packaged sets it. This is the half that is easy to lose: somebody adds the flag to an
     * {@code application.properties} to make a local run convenient, and every deployment built
     * from that jar trusts the header again — with no code change to notice in review.
     */
    @Test
    void nothingPackagedTurnsTheTestHeaderOn() throws IOException {
        var offenders = packagedConfig()
            .filter(p -> contains(p, WorkspaceHeaderEnabled.PROPERTY))
            .toList();

        assertThat(offenders)
            .as("%s may only ever be set by a test that opts in, never by packaged configuration",
                WorkspaceHeaderEnabled.PROPERTY)
            .isEmpty();
    }

    private static List<Path> productionSourcesReading(String needle) throws IOException {
        try (Stream<Path> sources = Files.walk(repoRoot())) {
            return sources
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.toString().contains("/src/main/java/"))
                .filter(p -> !EXEMPT.contains(p.getFileName().toString()))
                .filter(p -> contains(p, needle))
                .filter(p -> READS_A_HEADER.stream().anyMatch(read -> contains(p, read)))
                .toList();
        }
    }

    private static Stream<Path> packagedConfig() throws IOException {
        return Files.walk(repoRoot())
            .filter(p -> p.toString().contains("/src/main/resources/"))
            .filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml");
            });
    }

    /**
     * Fails loudly rather than passing vacuously. A scan that silently finds nothing to scan is a
     * green test protecting nothing, and it stays green forever — so the anchor is a directory this
     * repository cannot lose without somebody noticing.
     */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("modules/accounting"))
                && Files.isDirectory(candidate.resolve("apps/najem-app"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "cannot find the repository root from " + here + " — this guard would pass unchecked");
    }

    private static boolean contains(Path file, String needle) {
        try {
            return Files.readString(file).contains(needle);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file, e);
        }
    }
}
