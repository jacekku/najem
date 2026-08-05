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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Drives the real stack and asks Reporting to tell the story back.
 * <p>
 * The projector is a scheduled catch-up loop, so this waits for the read model to arrive rather
 * than assuming it is there — the eventual consistency is the design, and a test that pretended
 * otherwise would be testing a system nobody runs.
 */
@Testcontainers
@Tag("integration")
class TimelineTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static ConfigurableApplicationContext app;

    /**
     * The workspace every request names, reads included. The read fallback these assertions used to
     * rely on is deleted (najem-build seq 248): a timeline is one tenancy's entire story, so serving
     * it to a caller who named no agency was the most complete cross-tenant read in the tree.
     */
    private static final String DEV_WORKSPACE = "00000000-0000-0000-0000-000000000001";

    private static io.restassured.specification.RequestSpecification writing() {
        return given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON);
    }

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
                + "classpath:db/contacts,classpath:db/um,classpath:db/reporting");
        RestAssured.port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTenancysStoryIsReadableBackFromReportingAfterTheRealFlow() {
        String propertyId = writing()
            .body(Map.of("address", "ul. Testowa 9, Łódź",
                "owners", List.of(Map.of("contactId", "11111111-1111-1111-1111-111111111111",
                    "sharePercent", 100))))
            .post("/api/pm/properties").then().statusCode(200).extract().path("propertyId");

        String unitId = writing()
            .body(Map.of("name", "m. 7", "baseRent", 2400))
            .post("/api/pm/properties/" + propertyId + "/units")
            .then().statusCode(200).extract().path("unitId");

        writing().body(Map.of("reason", "ready to let"))
            .post("/api/pm/units/" + unitId + "/open").then().statusCode(200);

        String tenancyId = writing()
            .body(Map.of("unitId", unitId,
                "tenantContactIds", List.of("22222222-2222-2222-2222-222222222222"),
                "startDate", "2026-09-01", "endDate", "2027-09-01",
                "legalForm", "zwykly", "monthlyTotal", 2400, "rentDay", 10,
                "paymentReference", "NAJEM-TL-E2E"))
            .post("/api/pm/tenancies").then().statusCode(200).extract().path("tenancyId");

        writing().body(Map.of("activatedOn", "2026-09-01"))
            .post("/api/pm/tenancies/" + tenancyId + "/activate").then().statusCode(200);

        // The projector polls; wait for the story rather than assume it has caught up.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<String> kinds = reading()
                .get("/api/reporting/tenancies/" + tenancyId + "/timeline")
                .then().statusCode(200).extract().path("kind");
            assertThat(kinds).contains("tenancy-reserved", "tenancy-activated");
        });

        // The same facts, told about the unit rather than about the tenancy.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<String> kinds = reading()
                .get("/api/reporting/units/" + unitId + "/timeline")
                .then().statusCode(200).extract().path("kind");
            assertThat(kinds).contains("unit-added", "opened-to-rent", "tenancy-period-registered");
        });

        var occupancy = reading()
            .get("/api/reporting/properties/" + propertyId + "/occupancy?asOf=2026-10-01")
            .then().statusCode(200).extract().jsonPath();
        assertThat(occupancy.getInt("occupied")).isEqualTo(1);
        assertThat(occupancy.getInt("total")).isEqualTo(1);

        var board = reading()
            .get("/api/reporting/units?propertyId=" + propertyId + "&asOf=2026-10-01")
            .then().statusCode(200).extract().jsonPath();
        assertThat(board.getString("[0].name")).isEqualTo("m. 7");
        assertThat(board.getString("[0].marketState")).isEqualTo("open");
        assertThat(board.getString("[0].currentTenancyId")).isEqualTo(tenancyId);
    }

    /** Another agency's tenancy is not forbidden, it is invisible — the query is the boundary. */
    @Test
    void tellsAnotherWorkspaceNothingAtAll() {
        List<Object> entries = given()
            .header("X-Workspace-Id", "99999999-9999-9999-9999-999999999999")
            .get("/api/reporting/tenancies/33333333-3333-3333-3333-333333333333/timeline")
            .then().statusCode(200).extract().path("$");

        assertThat(entries).isEmpty();
    }

}
