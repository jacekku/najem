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
import pl.najem.fakebank.FakeBankApplication;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class WalkingSkeletonTest {

    /**
     * The workspace every write must name (rule 7). Reads still fall back to it when no header is
     * sent — the Phase 1 scaffold — but a write may not, because a write with no header modifies
     * books nobody named and returns success. PM's units and tenancies take no header at all: they
     * inherit the workspace from the property they belong to.
     */
    private static final String DEV_WORKSPACE = "00000000-0000-0000-0000-000000000001";

    static final String IBAN = "PL61109010140000071219812874";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static ConfigurableApplicationContext bank;
    static ConfigurableApplicationContext app;
    static int bankPort;

    @BeforeAll
    static void start() {
        bank = new SpringApplicationBuilder(FakeBankApplication.class)
            .run("--server.port=0",
                "--spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                    // najem-app puts spring-security on the shared e2e classpath (UserManagement's
                    // resource server), which would otherwise auto-secure FakeBank's endpoints too
                    // and 401 the seed call. FakeBank has no accounts and no security of its own.
                    + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration");
        bankPort = Integer.parseInt(bank.getEnvironment().getProperty("local.server.port"));
        app = new SpringApplicationBuilder(NajemApplication.class).run(
            "--server.port=0",
            "--spring.datasource.url=" + pg.getJdbcUrl(),
            "--spring.datasource.username=" + pg.getUsername(),
            "--spring.datasource.password=" + pg.getPassword(),
            "--spring.flyway.locations=classpath:db/eventstore,classpath:db/pm,classpath:db/acc",
            "--najem.bank.base-url=http://localhost:" + bankPort,
            "--najem.bank.iban=" + IBAN);
        RestAssured.port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
        if (bank != null) bank.close();
    }

    @Test
    void tenantPaysAndBoardTurnsGreen() {
        String propertyId = given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON)
            .body(Map.of("address", "Testowa 1, Kraków"))
            .post("/api/pm/properties").then().statusCode(200).extract().path("propertyId");
        String unitId = given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON)
            .body(Map.of("name", "M1", "baseRent", "2500"))
            .post("/api/pm/properties/" + propertyId + "/units").then().statusCode(200).extract().path("unitId");
        // A tenancy needs at least one tenant contact (domain model §3) and the monthly figure
        // is now a total with an optional component breakdown — this reservation declares none,
        // so the collapse rule applies and the whole amount is czynsz.
        String tenancyId = given().header("X-Workspace-Id", DEV_WORKSPACE)
            .contentType(ContentType.JSON)
            .body(Map.of("unitId", unitId,
                "tenantContactIds", java.util.List.of(java.util.UUID.randomUUID().toString()),
                "startDate", "2026-09-01", "endDate", "2027-08-31",
                "legalForm", "zwykly", "monthlyTotal", "2500",
                "paymentReference", "NAJEM/M1/2026"))
            .post("/api/pm/tenancies").then().statusCode(200).extract().path("tenancyId");
        given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON)
            .body(Map.of("activatedOn", "2026-09-01"))
            .post("/api/pm/tenancies/" + tenancyId + "/activate").then().statusCode(200);

        // The tenancy appears on the board owing its first month. Which colour that is depends on
        // where the wall clock sits relative to the due date — yellow before it, red after — so the
        // skeleton asserts what it actually cares about: the tenancy is on the board and not green.
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            String status = boardStatus(tenancyId);
            return status != null && !"green".equals(status);
        });

        given().port(bankPort).contentType(ContentType.JSON)
            .body(Map.of("id", "tx-1", "amount", "2500", "title", "NAJEM/M1/2026",
                "bookingDate", java.time.LocalDate.now().toString()))
            .post("/api/accounts/" + IBAN + "/transactions").then().statusCode(201);

        given().header("X-Workspace-Id", DEV_WORKSPACE)
            .post("/api/acc/ingest/fetch").then().statusCode(200);

        String paymentId = given().get("/api/acc/suggestions")
            .then().statusCode(200).extract().path("[0].paymentId");
        assertThat(paymentId).isNotNull();
        given().header("X-Workspace-Id", DEV_WORKSPACE)
            .post("/api/acc/payments/" + UUID.fromString(paymentId) + "/confirm").then().statusCode(200);

        assertThat(boardStatus(tenancyId)).isEqualTo("green");
    }

    private static String boardStatus(String tenancyId) {
        var board = given().get("/api/acc/board").then().statusCode(200)
            .extract().jsonPath().getList("", Map.class);
        return board.stream()
            .filter(row -> tenancyId.equals(String.valueOf(row.get("tenancyId"))))
            .map(row -> String.valueOf(row.get("status")))
            .findFirst().orElse(null);
    }
}
