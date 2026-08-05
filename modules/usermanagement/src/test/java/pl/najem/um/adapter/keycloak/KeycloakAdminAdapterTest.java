package pl.najem.um.adapter.keycloak;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real Keycloak. Tagged because it dominates build time — pulling and booting the image
 * costs minutes, against seconds for every other suite in the repo — so it is opt-in locally
 * ({@code -PkeycloakTests}) and always on in CI. Skipping it locally is a speed trade, not a
 * coverage one: nothing else proves the adapter speaks Keycloak's actual API.
 */
@Tag("keycloak")
@Testcontainers
@Tag("integration")
class KeycloakAdminAdapterTest {

    @Container
    static GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
        .withCommand("start-dev")
        .withExposedPorts(8080)
        .waitingFor(Wait.forHttp("/realms/master").forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(3));

    static KeycloakAdminAdapter adapter;

    @BeforeAll
    static void setUp() {
        String baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
        // The master realm and its admin-cli client exist out of the box; the tests provision into master,
        // while docker-compose (Task 11) imports the dedicated "najem" realm.
        adapter = new KeycloakAdminAdapter(baseUrl, "master", "admin", "admin");
    }

    @Test
    void provisioningCreatesTheUserAndReturnsItsSubject() {
        var subject = adapter.provision("created@example.com");

        assertThat(subject).isNotNull();
    }

    @Test
    void provisioningIsIdempotentPerEmail() {
        var first = adapter.provision("provisioned@example.com");
        var second = adapter.provision("provisioned@example.com");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void differentEmailsGetDifferentSubjects() {
        var one = adapter.provision("one@example.com");
        var two = adapter.provision("two@example.com");

        assertThat(one).isNotEqualTo(two);
    }
}
