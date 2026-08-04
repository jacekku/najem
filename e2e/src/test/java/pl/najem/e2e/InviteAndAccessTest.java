package pl.najem.e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.app.NajemApplication;
import pl.najem.um.application.KeycloakAdminPort;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves invite-only end to end: accepting an invitation is the only way into a workspace.
 * Keycloak itself is stubbed — the real adapter has its own container test; what this exercises is
 * NAJEM's flow through the running application.
 */
@Testcontainers
class InviteAndAccessTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    /** Subject of the config-seeded platform operator that bootstraps a fresh deployment. */
    private static final String OPERATOR_SUBJECT = "00000000-0000-0000-0000-0000000000ff";

    static ConfigurableApplicationContext app;

    @Configuration
    static class StubKeycloak {
        @Bean
        KeycloakAdminPort keycloakAdminPort() {
            var subjects = new ConcurrentHashMap<String, UUID>();
            return email -> subjects.computeIfAbsent(email, e -> UUID.randomUUID());
        }
    }

    @BeforeAll
    static void start() {
        // Command-line args, not builder.properties(...): the latter registers DEFAULT properties,
        // which application.yml then overrides, so the app would dial localhost:5432.
        app = new SpringApplicationBuilder(NajemApplication.class, StubKeycloak.class).run(
            "--server.port=0",
            // Every deployment names its bank: the fake one is opt-in and its flag has no
            // default, so without these the app has no BankStatementPort and refuses to
            // start. This suite never calls the bank; it only has to name one.
            "--najem.bank.fake.enabled=true",
            "--najem.bank.base-url=http://localhost:8081",
            "--najem.bank.iban=PL61109010140000071219812874",
            // Explicit: rule 7 forbids acquiring permit-all by omission.
            "--najem.security.permit-all=true",
            "--spring.datasource.url=" + pg.getJdbcUrl(),
            "--spring.datasource.username=" + pg.getUsername(),
            "--spring.datasource.password=" + pg.getPassword(),
            "--najem.bootstrap.operator-subject=" + OPERATOR_SUBJECT,
            "--spring.main.allow-bean-definition-overriding=true");
        RestAssured.port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    private static String createWorkspace(String name) {
        return given().contentType(JSON).body(Map.of("name", name))
            .post("/api/um/workspaces").then().statusCode(200).extract().path("workspaceId");
    }

    @Test
    void anInvitedManagerJoinsTheWorkspaceAndTheCreatorIsItsAdmin() {
        String workspaceId = createWorkspace("Agencja E2E");

        // the operator that created it is already an ADMIN — otherwise nobody could ever invite
        var mine = given().get("/api/um/me").then().statusCode(200).extract();
        assertThat(mine.path("workspaces.find { it.workspaceId == '" + workspaceId + "' }.role").toString())
            .isEqualTo("ADMIN");

        String token = given().contentType(JSON)
            .body(Map.of("email", "manager@example.com", "role", "MANAGER", "expiresOn", "2026-12-31"))
            .post("/api/um/workspaces/" + workspaceId + "/invitations")
            .then().statusCode(200).extract().path("token");

        String userId = given().contentType(JSON).body(Map.of("token", token))
            .post("/api/um/invitations/accept").then().statusCode(200).extract().path("userId");

        assertThat(userId).isNotBlank();
    }

    @Test
    void aTokenCannotBeReplayedAndAFabricatedOneCreatesNobody() {
        String workspaceId = createWorkspace("Agencja Replay");
        String token = given().contentType(JSON)
            .body(Map.of("email", "once@example.com", "role", "MANAGER", "expiresOn", "2026-12-31"))
            .post("/api/um/workspaces/" + workspaceId + "/invitations")
            .then().statusCode(200).extract().path("token");

        given().contentType(JSON).body(Map.of("token", token))
            .post("/api/um/invitations/accept").then().statusCode(200);

        // replay of a spent token, and a token nobody ever issued: both refused
        given().contentType(JSON).body(Map.of("token", token))
            .post("/api/um/invitations/accept").then().statusCode(500);
        given().contentType(JSON).body(Map.of("token", "fabricated-token"))
            .post("/api/um/invitations/accept").then().statusCode(500);
    }

    @Test
    void aRevokedInvitationCannotBeAccepted() {
        String workspaceId = createWorkspace("Agencja Cofnieta");
        var issued = given().contentType(JSON)
            .body(Map.of("email", "revoked@example.com", "role", "MANAGER", "expiresOn", "2026-12-31"))
            .post("/api/um/workspaces/" + workspaceId + "/invitations")
            .then().statusCode(200).extract();

        given().delete("/api/um/workspaces/" + workspaceId + "/invitations/" + issued.path("invitationId"))
            .then().statusCode(204);

        given().contentType(JSON).body(Map.of("token", issued.path("token").toString()))
            .post("/api/um/invitations/accept").then().statusCode(500);
    }
}
