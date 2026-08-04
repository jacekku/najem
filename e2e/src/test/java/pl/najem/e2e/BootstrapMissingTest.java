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
