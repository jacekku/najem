package pl.najem.app;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packaged {@code application.yml} may not answer a question only the operator can answer.
 *
 * <p>A default in this file is not a convenience — it is a production decision written by whoever
 * happened to be developing at the time. {@code najem.bank.iban} answered "whose money is this?"
 * with one agency's account for every workspace in the system; {@code spring.datasource.password}
 * answers "which database?" with whatever is listening on 5432. Both would have started cleanly and
 * done the wrong thing quietly, which is the failure rule 7 exists to prevent: absent means refuse
 * to start, never guess.
 *
 * <p>This is a <strong>scan</strong>, not a list of the values found wrong today. A ruling that
 * five keys must carry no default holds until someone adds a sixth — which is exactly how four
 * write endpoints were born without a workspace header inside ninety minutes. Any new key under the
 * scanned prefixes fails this test on the commit that introduces it.
 */
class PackagedConfigTest {

    /** Prefixes whose values name an environment: a bank, a database, an identity provider. */
    private static final List<String> SCANNED_PREFIXES = List.of("najem", "spring.datasource");

    /**
     * Keys that still carry a default, each with the agent who owns removing it.
     *
     * <p>This is a debt register, not an exemption list. It is deliberately the inverse of a
     * fix-list: everything <em>not</em> named here is checked, so the guard covers keys nobody has
     * thought of yet. The test also fails when an entry here no longer appears in the file, so a
     * key cannot be fixed and leave its excuse behind.
     */
    private static final Set<String> KNOWN_UNFIXED = new LinkedHashSet<>(List.of(
        "najem.keycloak.base-url",        // najem-frontend, with the SecurityConfig fail-closed work
        "najem.keycloak.realm",           // najem-frontend
        "najem.keycloak.admin-username",  // najem-frontend
        "najem.keycloak.admin-password",  // najem-frontend
        "spring.datasource.url",          // unowned as of seq 240 — raised by najem-reviewer
        "spring.datasource.username",     // unowned
        "spring.datasource.password"      // unowned
    ));

    @Test
    void noEnvironmentValueCarriesADefault() {
        Set<String> present = new TreeSet<>(scannedKeys());

        assertThat(present)
            .as("the scan must reach the file at all — an empty result would pass vacuously")
            .isNotEmpty();

        assertThat(present)
            .as("""
                A packaged default for these keys is a production decision made by a developer. \
                Remove the key from application.yml and let the @Value or @ConfigurationProperties \
                binding fail at startup. If a key here is genuinely someone else's to fix, add it \
                to KNOWN_UNFIXED with the owner's name — do not delete this assertion.""")
            .containsExactlyInAnyOrderElementsOf(KNOWN_UNFIXED);
    }

    /**
     * A stale entry in {@link #KNOWN_UNFIXED} is worse than no register: it reads as "someone is on
     * it" long after they were. Covered by the assertion above, which is exact in both directions;
     * this test exists to say so where a reader of the register will look.
     */
    @Test
    void theDebtRegisterNamesOnlyKeysThatStillCarryADefault() {
        assertThat(scannedKeys())
            .as("KNOWN_UNFIXED lists a key that application.yml no longer sets — delete the entry")
            .containsAll(KNOWN_UNFIXED);
    }

    /** Every leaf key under the scanned prefixes that has a value in the packaged config. */
    private static List<String> scannedKeys() {
        Map<String, Object> yaml = load();
        List<String> leaves = new ArrayList<>();
        flatten("", yaml, leaves);
        return leaves.stream()
            .filter(key -> SCANNED_PREFIXES.stream().anyMatch(p -> key.equals(p) || key.startsWith(p + ".")))
            .toList();
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

    private static Map<String, Object> load() {
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new AssertionError("Could not read the packaged application.yml", e);
        }
    }
}
