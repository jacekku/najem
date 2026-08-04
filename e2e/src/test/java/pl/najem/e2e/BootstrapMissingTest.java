package pl.najem.e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.app.NajemApplication;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;

/**
 * Fail-closed check: with security off AND no configured platform operator, there is nobody for a
 * request to act as. Creating a workspace must be refused rather than proceeding anonymously — an
 * unauthenticated caller silently becoming a workspace ADMIN is the worst outcome available here.
 */
@Testcontainers
class BootstrapMissingTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static ConfigurableApplicationContext app;

    @BeforeAll
    static void start() {
        // deliberately no najem.bootstrap.operator-subject
        app = new SpringApplicationBuilder(NajemApplication.class).run(
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
            "--spring.datasource.password=" + pg.getPassword());
        RestAssured.port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @Test
    void creatingAWorkspaceWithNoCallerAndNoOperatorIsRefused() {
        given().contentType(JSON).body(Map.of("name", "Agencja Bez Operatora"))
            .post("/api/um/workspaces")
            .then().statusCode(403);
    }
}
