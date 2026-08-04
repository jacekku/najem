package pl.najem.app;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No packaged configuration file may ship a secret, under any key, in any module.
 *
 * <p>This is deliberately <strong>not</strong> scoped by prefix. {@link PackagedConfigTest} scans
 * {@code najem.*} and {@code spring.datasource.*} for defaults, and that bounded scope is the right
 * one there — a default is only wrong where the value names an environment. It is the wrong scope
 * for a secret, because a secret is wrong wherever it appears and the next one will not be under a
 * prefix anybody listed. {@code spring.mail.password} and
 * {@code spring.security.oauth2.client.registration.*.client-secret} are both invisible to a
 * prefix list written today, and the second is the shape of configuration this application is about
 * to acquire.
 *
 * <p>So the test matches on the <em>key name</em> and walks <em>every</em> packaged resource in the
 * tree rather than one classpath file. {@code apps/fakebank} has its own {@code application.yml}
 * and was previously unscanned by construction rather than by decision.
 *
 * <p><strong>Out of scope, deliberately:</strong> {@code docker-compose.yml} carries
 * {@code POSTGRES_PASSWORD} and {@code KC_BOOTSTRAP_ADMIN_PASSWORD}. It is a developer's local
 * stack, not something this application packages, and a guard that fails on it would teach whoever
 * hits it to add an exclusion — after which the exclusion, not the rule, is what the next reader
 * inherits. Note that once the datasource credentials leave {@code application.yml}, compose
 * becomes the only place they exist, which changes what that file is; that is a decision to write
 * down somewhere, not a gap to close here.
 */
class PackagedSecretsTest {

    /** Substring match on any segment of the key path, case-insensitive. */
    private static final Pattern SECRET_NAMED =
        Pattern.compile("password|secret|token|credential", Pattern.CASE_INSENSITIVE);

    /**
     * Keys that still ship a secret, each with the agent who owns removing it.
     *
     * <p>A register rather than an exclusion list: it does not widen or narrow what is scanned, it
     * only lets a guard covering someone else's debt be green on the day it lands. Everything not
     * named here fails, including keys nobody has thought of. The register is checked in both
     * directions, so an entry cannot outlive the debt it describes.
     */
    private static final Map<String, String> KNOWN_UNFIXED = new LinkedHashMap<>(Map.of(
        "apps/najem-app|spring.datasource.password", "unowned as of seq 267"
    ));

    @Test
    void noPackagedConfigShipsASecret() {
        List<String> found = secretNamedValues();

        assertThat(found)
            .as("""
                A packaged secret is a credential every deployment shares and nobody chose. Remove \
                the key and let the binding fail at startup. If it is genuinely someone else's to \
                fix, add it to KNOWN_UNFIXED with the owner — do not narrow the pattern and do not \
                exclude the file.""")
            .containsExactlyInAnyOrderElementsOf(KNOWN_UNFIXED.keySet());
    }

    /**
     * The scan must be shown to have reached something. A walk rooted at the wrong directory and a
     * tree with no secrets in it produce the same empty list, and only one of them is good news.
     *
     * <p>Two assertions rather than one, per the same reasoning: finding the files proves the root
     * resolved; it does not prove any of them parsed. A YAML loader returning empty maps would
     * satisfy the first and fail the second.
     */
    @Test
    void theWalkReachesTheConfigFilesAndReadsThem() {
        List<Path> configs = packagedConfigs();

        assertThat(configs)
            .as("the walk must find the packaged configs — an empty walk would pass vacuously")
            .hasSizeGreaterThanOrEqualTo(2);
        assertThat(configs.stream().map(p -> p.getFileName().toString()))
            .allMatch(name -> name.endsWith(".yml") || name.endsWith(".yaml"));

        long keys = configs.stream().mapToLong(config -> flatten(load(config)).size()).sum();
        assertThat(keys)
            .as("the configs must actually parse — files found but unread would pass vacuously")
            .isPositive();
    }

    /** Every key in every packaged config whose path names a secret, as {@code module|key}. */
    private static List<String> secretNamedValues() {
        List<String> hits = new ArrayList<>();
        for (Path config : packagedConfigs()) {
            String module = repoRoot().relativize(config).getParent().getParent()
                .getParent().getParent().toString();
            for (String key : flatten(load(config))) {
                if (SECRET_NAMED.matcher(key).find()) {
                    hits.add(module + "|" + key);
                }
            }
        }
        return hits;
    }

    /** Every {@code src/main/resources} YAML in the tree. Build output is not source. */
    private static List<Path> packagedConfigs() {
        Path root = repoRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String s = root.relativize(p).toString();
                    return !s.contains("/build/") && s.contains("src/main/resources")
                        && (s.endsWith(".yml") || s.endsWith(".yaml"));
                })
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new AssertionError("Could not walk the repository from " + root, e);
        }
    }

    /**
     * The repository root, found by walking up to the Gradle settings file rather than assuming
     * how deep the test's working directory is. A hardcoded {@code ../..} silently becomes a walk
     * of the wrong subtree when a module moves, and a walk of the wrong subtree finds no secrets.
     */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new AssertionError("Could not locate settings.gradle.kts above "
                + Path.of("").toAbsolutePath());
        }
        return dir;
    }

    private static List<String> flatten(Map<String, Object> node) {
        List<String> leaves = new ArrayList<>();
        flatten("", node, leaves);
        return leaves;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> node, List<String> into) {
        node.forEach((key, value) -> {
            String path = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> child) {
                flatten(path, (Map<String, Object>) child, into);
            } else if (value != null) {
                into.add(path);
            }
        });
    }

    private static Map<String, Object> load(Path config) {
        try (InputStream in = Files.newInputStream(config)) {
            Map<String, Object> yaml = new Yaml().load(in);
            return yaml == null ? Map.of() : yaml;
        } catch (Exception e) {
            throw new AssertionError("Could not read " + config, e);
        }
    }
}
