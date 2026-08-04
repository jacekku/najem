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

    /**
     * The dev workspace, sent explicitly on every write. The DEV_WORKSPACE_ID fallback is now
     * reads-only: a write with no header is refused rather than landing in a workspace nobody
     * named. {@link #aWriteWithoutAWorkspaceIsRefused()} pins that.
     */
    private static final String DEV_WORKSPACE = "00000000-0000-0000-0000-000000000001";

    private static io.restassured.specification.RequestSpecification writing() {
        return given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON);
    }

    @BeforeAll
    static void start() {
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
        String contactId = writing()
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
        writing()
            .body(Map.of("unitId", unit12, "willingToPay", "2400", "desiredStart", "2026-10-01"))
            .post("/api/contacts/" + contactId + "/interests").then().statusCode(201);
        writing()
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
        writing().body(Map.of("reason", "ledger-referenced"))
            .post("/api/contacts/" + contactId + "/retention-holds").then().statusCode(204);
        writing().delete("/api/contacts/" + contactId + "?on=2026-08-05").then().statusCode(409);
        given().get("/api/contacts/" + contactId).then().statusCode(200);
        assertThat(given().get("/api/contacts/erasure-due?asOf=2026-08-05")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .doesNotContain(contactId);

        // Once released, erasure removes the personal data for good.
        writing().delete("/api/contacts/" + contactId + "/retention-holds/ledger-referenced?on=2026-08-05")
            .then().statusCode(204);
        writing().delete("/api/contacts/" + contactId + "?on=2026-08-05").then().statusCode(204);

        given().get("/api/contacts/" + contactId).then().statusCode(404);
        assertThat(given().get("/api/contacts/units/" + unit12 + "/interests")
            .then().statusCode(200).extract().jsonPath().getList("")).isEmpty();
        assertThat(given().get("/api/contacts?email=anna@example.com")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .doesNotContain(contactId);
    }

    /**
     * A write that names no workspace is refused, not defaulted.
     * <p>
     * The releasing case is the one that decides it: a retention hold is what stops an erasure, so
     * a release aimed at nowhere would leave the real hold standing while the operator believes
     * they lifted it — a silent failure whose only symptom is data that quietly refuses to be
     * erased. Reads keep the dev fallback; nothing is lost by showing a caller an empty list.
     */
    @Test
    void aWriteWithoutAWorkspaceIsRefused() {
        given().contentType(ContentType.JSON)
            .body(Map.of("givenName", "Nobody", "surname", "Nowhere", "lawfulBasis", "consent"))
            .post("/api/contacts").then().statusCode(400);

        given().delete("/api/contacts/" + UUID.randomUUID() + "/retention-holds/ledger-referenced")
            .then().statusCode(400);

        // Reads are unaffected: no header still means the dev workspace.
        given().get("/api/contacts/erasure-due?asOf=2026-08-05").then().statusCode(200);
    }
}
