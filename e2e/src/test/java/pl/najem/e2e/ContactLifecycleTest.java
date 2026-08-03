package pl.najem.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
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
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lead lifecycle from the domain walkthrough: Anna calls, is captured as a lead
 * interested in two units, goes cold, and finally asks to be forgotten.
 * No X-Workspace-Id header is sent, so the dev-workspace default is exercised —
 * what the app runs on until UserManagement lands Keycloak claims.
 */
@Testcontainers
class ContactLifecycleTest {

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
            "--spring.flyway.locations=classpath:db/eventstore,classpath:db/pm,classpath:db/acc,"
                + "classpath:db/contacts");
        RestAssured.port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @Test
    void leadIsCapturedLinkedToUnitsAndErasedOnRequest() {
        String contactId = given().contentType(ContentType.JSON)
            .body(Map.of("givenName", "Anna", "surname", "Kowalska",
                "email", "anna@example.com", "phone", "+48600100200",
                "lawfulBasis", "legitimate-interest",
                "infoClauseServedAt", "2026-08-03",
                "retainUntil", "2026-08-04"))
            .post("/api/contacts").then().statusCode(201).extract().path("contactId");

        given().get("/api/contacts/" + contactId).then().statusCode(200)
            .body("givenName", org.hamcrest.Matchers.equalTo("Anna"));

        String unit12 = UUID.randomUUID().toString();
        String unit14 = UUID.randomUUID().toString();
        given().contentType(ContentType.JSON)
            .body(Map.of("unitId", unit12, "willingToPay", "2400", "desiredStart", "2026-10-01"))
            .post("/api/contacts/" + contactId + "/interests").then().statusCode(201);
        given().contentType(ContentType.JSON)
            .body(Map.of("unitId", unit14, "willingToPay", "2400", "desiredStart", "2026-10-01"))
            .post("/api/contacts/" + contactId + "/interests").then().statusCode(201);

        assertThat(given().get("/api/contacts/units/" + unit12 + "/interests")
            .then().statusCode(200).extract().jsonPath().getList("")).hasSize(1);

        // The manager checks whether this person is already known before capturing again.
        assertThat(given().get("/api/contacts?email=anna@example.com")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .contains(contactId);

        // The lead goes cold and surfaces on the erasure-due report — reported, not deleted.
        assertThat(given().get("/api/contacts/erasure-due?asOf=2026-08-05")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .contains(contactId);

        // An accounting-style retention hold blocks erasure.
        given().contentType(ContentType.JSON).body(Map.of("reason", "ledger-referenced"))
            .post("/api/contacts/" + contactId + "/retention-holds").then().statusCode(204);
        given().delete("/api/contacts/" + contactId + "?on=2026-08-05").then().statusCode(409);
        given().get("/api/contacts/" + contactId).then().statusCode(200);
        assertThat(given().get("/api/contacts/erasure-due?asOf=2026-08-05")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .doesNotContain(contactId);

        // Once released, erasure removes the personal data for good.
        given().delete("/api/contacts/" + contactId + "/retention-holds/ledger-referenced?on=2026-08-05")
            .then().statusCode(204);
        given().delete("/api/contacts/" + contactId + "?on=2026-08-05").then().statusCode(204);

        given().get("/api/contacts/" + contactId).then().statusCode(404);
        assertThat(given().get("/api/contacts/units/" + unit12 + "/interests")
            .then().statusCode(200).extract().jsonPath().getList("")).isEmpty();
        assertThat(given().get("/api/contacts?email=anna@example.com")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .doesNotContain(contactId);
    }
}
