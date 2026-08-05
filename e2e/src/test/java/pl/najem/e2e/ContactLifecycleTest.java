package pl.najem.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
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
 * <p>
 * Every request names the workspace, reads included. The dev-workspace fallback this test used to
 * exercise on reads is deleted (najem-build seq 248) — omitting the header served workspace
 * {@code …0001}'s personal data and erasure worklist to a caller who named no agency.
 */
@Testcontainers
@Tag("integration")
class ContactLifecycleTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static ConfigurableApplicationContext app;

    /**
     * The workspace every request in this test names. It is no longer a fallback anybody can reach
     * by omission — there is no fallback; {@link #aRequestWithoutAWorkspaceIsRefused()} pins that
     * for both a write and a read.
     */
    private static final String DEV_WORKSPACE = "00000000-0000-0000-0000-000000000001";

    private static io.restassured.specification.RequestSpecification writing() {
        return given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON);
    }

    /** Reads name the workspace too, now that omitting it is refused rather than defaulted. */
    private static io.restassured.specification.RequestSpecification reading() {
        return given().header("X-Workspace-Id", DEV_WORKSPACE);
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

        reading().get("/api/contacts/" + contactId).then().statusCode(200)
            .body("givenName", org.hamcrest.Matchers.equalTo("Anna"));

        String unit12 = UUID.randomUUID().toString();
        String unit14 = UUID.randomUUID().toString();
        writing()
            .body(Map.of("unitId", unit12, "willingToPay", "2400", "desiredStart", "2026-10-01"))
            .post("/api/contacts/" + contactId + "/interests").then().statusCode(201);
        writing()
            .body(Map.of("unitId", unit14, "willingToPay", "2400", "desiredStart", "2026-10-01"))
            .post("/api/contacts/" + contactId + "/interests").then().statusCode(201);

        assertThat(reading().get("/api/contacts/units/" + unit12 + "/interests")
            .then().statusCode(200).extract().jsonPath().getList("")).hasSize(1);

        // The manager checks whether this person is already known before capturing again.
        assertThat(reading().get("/api/contacts?email=anna@example.com")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .contains(contactId);

        // The lead goes cold and surfaces on the erasure-due report — reported, not deleted.
        assertThat(reading().get("/api/contacts/erasure-due?asOf=2026-08-05")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .contains(contactId);

        // An accounting-style retention hold blocks erasure.
        writing().body(Map.of("reason", "ledger-referenced"))
            .post("/api/contacts/" + contactId + "/retention-holds").then().statusCode(204);
        writing().delete("/api/contacts/" + contactId + "?on=2026-08-05").then().statusCode(409);
        reading().get("/api/contacts/" + contactId).then().statusCode(200);
        assertThat(reading().get("/api/contacts/erasure-due?asOf=2026-08-05")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .doesNotContain(contactId);

        // Once released, erasure removes the personal data for good.
        writing().delete("/api/contacts/" + contactId + "/retention-holds/ledger-referenced?on=2026-08-05")
            .then().statusCode(204);
        writing().delete("/api/contacts/" + contactId + "?on=2026-08-05").then().statusCode(204);

        reading().get("/api/contacts/" + contactId).then().statusCode(404);
        assertThat(reading().get("/api/contacts/units/" + unit12 + "/interests")
            .then().statusCode(200).extract().jsonPath().getList("")).isEmpty();
        assertThat(reading().get("/api/contacts?email=anna@example.com")
            .then().statusCode(200).extract().jsonPath().getList("", String.class))
            .doesNotContain(contactId);
    }

    /**
     * Any request that names no workspace is refused — writes and reads alike.
     * <p>
     * The releasing case decides it for writes: a retention hold is what stops an erasure, so a
     * release aimed at nowhere would leave the real hold standing while the operator believes they
     * lifted it — a silent failure whose only symptom is data that quietly refuses to be erased.
     * <p>
     * <b>The read assertion here used to expect 200, and inverting it is the point of the change.</b>
     * The old comment read "reads are unaffected: no header still means the dev workspace", and the
     * justification alongside it was that nothing is lost by showing a caller an empty list. That
     * was wrong on its own terms — the caller was not shown an empty list, they were shown workspace
     * {@code …0001}'s. For {@code erasure-due} that is a list of named people whose retention has
     * expired, served to a request that identified no agency and carried no credential.
     */
    @Test
    void aRequestWithoutAWorkspaceIsRefused() {
        given().contentType(ContentType.JSON)
            .body(Map.of("givenName", "Nobody", "surname", "Nowhere", "lawfulBasis", "consent"))
            .post("/api/contacts").then().statusCode(400);

        given().delete("/api/contacts/" + UUID.randomUUID() + "/retention-holds/ledger-referenced")
            .then().statusCode(400);

        given().get("/api/contacts/erasure-due?asOf=2026-08-05").then().statusCode(400);
        given().get("/api/contacts?email=anna@example.com").then().statusCode(400);
    }
}
