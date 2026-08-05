package pl.najem.e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.app.NajemApplication;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application starts, reaches a database, and answers.
 *
 * <p><strong>This is the one container test that is deliberately not {@code @Tag("integration")}.</strong>
 * Every other class that boots a container is tagged and excluded from {@code ./gradlew build},
 * which made a green fast build say nothing at all about whether the thing runs — every module
 * could pass its own tests while the assembled application failed to start, and nobody would learn
 * that until a merge. One boot is worth the minute it costs.
 *
 * <p>It asserts only what its name claims. Behaviour lives in the tagged suites — this one must
 * stay cheap, or the exception stops being worth making. Adding a scenario here is how a fast tier
 * turns back into a slow one.
 */
@Testcontainers
class AppStandsTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static ConfigurableApplicationContext app;

    @BeforeAll
    static void start() {
        app = new SpringApplicationBuilder(NajemApplication.class).run(
            "--server.port=0",
            "--spring.datasource.url=" + pg.getJdbcUrl(),
            "--spring.datasource.username=" + pg.getUsername(),
            "--spring.datasource.password=" + pg.getPassword(),
            // Explicit: rule 7 forbids acquiring permit-all by omission, and the application
            // refuses to start rather than choosing it. That refusal is worth knowing about — it
            // is the first thing this test hit — so it is named here rather than worked around.
            "--najem.security.permit-all=true",
            // A bank port must exist or the application refuses to start, which is the behaviour
            // roadmap rule 7 wanted. Nothing here calls it, so it may point nowhere.
            "--najem.bank.fake.enabled=true",
            "--najem.bank.base-url=http://localhost:1");
        RestAssured.port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void theApplicationStarts() {
        assertThat(app.isRunning()).isTrue();
    }

    /**
     * Not merely "a connection opened": every migration location the deployment declares has run
     * and none of them failed. {@code fail-on-missing-locations} catches a module that fell off the
     * classpath; this catches one that is present and whose schema did not apply.
     */
    @Test
    void everyDeclaredMigrationHasRunAgainstTheDatabase() {
        var jdbc = app.getBean(JdbcTemplate.class);

        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class))
            .isPositive();
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where not success", Integer.class))
            .isZero();
    }

    /**
     * The web layer is wired and serving. The status is not asserted: unauthenticated is a correct
     * answer from an application that fails closed, and pinning it here would make this test fail
     * for a security change it has no opinion about. That the port answers at all is the claim.
     */
    @Test
    void theWebLayerAnswers() {
        int status = given().get("/api/properties").statusCode();

        assertThat(status).isLessThan(500);
    }
}
