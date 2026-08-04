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
class TimelineTest {

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
        String propertyId = given().contentType(ContentType.JSON)
            .body(Map.of("address", "ul. Testowa 9, Łódź",
                "owners", List.of(Map.of("contactId", "11111111-1111-1111-1111-111111111111",
                    "sharePercent", 100))))
            .post("/api/pm/properties").then().statusCode(200).extract().path("propertyId");

        String unitId = given().contentType(ContentType.JSON)
            .body(Map.of("name", "m. 7", "baseRent", 2400))
            .post("/api/pm/properties/" + propertyId + "/units")
            .then().statusCode(200).extract().path("unitId");

        given().contentType(ContentType.JSON).body(Map.of("reason", "ready to let"))
            .post("/api/pm/units/" + unitId + "/open").then().statusCode(200);

        String tenancyId = given().contentType(ContentType.JSON)
            .body(Map.of("unitId", unitId,
                "tenantContactIds", List.of("22222222-2222-2222-2222-222222222222"),
                "startDate", "2026-09-01", "endDate", "2027-09-01",
                "legalForm", "zwykly", "monthlyTotal", 2400, "rentDay", 10,
                "paymentReference", "NAJEM-TL-E2E"))
            .post("/api/pm/tenancies").then().statusCode(200).extract().path("tenancyId");

        given().contentType(ContentType.JSON).body(Map.of("activatedOn", "2026-09-01"))
            .post("/api/pm/tenancies/" + tenancyId + "/activate").then().statusCode(200);

        // The projector polls; wait for the story rather than assume it has caught up.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<String> kinds = given()
                .get("/api/reporting/tenancies/" + tenancyId + "/timeline")
                .then().statusCode(200).extract().path("kind");
            assertThat(kinds).contains("tenancy-reserved", "tenancy-activated");
        });

        // The same facts, told about the unit rather than about the tenancy.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<String> kinds = given()
                .get("/api/reporting/units/" + unitId + "/timeline")
                .then().statusCode(200).extract().path("kind");
            assertThat(kinds).contains("unit-added", "opened-to-rent", "tenancy-period-registered");
        });

        var occupancy = given()
            .get("/api/reporting/properties/" + propertyId + "/occupancy?asOf=2026-10-01")
            .then().statusCode(200).extract().jsonPath();
        assertThat(occupancy.getInt("occupied")).isEqualTo(1);
        assertThat(occupancy.getInt("total")).isEqualTo(1);

        var board = given()
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
