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
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

/**
 * PM and accounting both name a stream after the same tenancy: PM writes {@code Tenancy} and
 * accounting writes {@code TenancyLedger} under one tenancy id. Until the event store took the
 * stream type into account, those were one stream with two names -- so once a tenancy had been
 * charged, every PM operation that rehydrated it died on the first accounting event it met.
 * <p>
 * That is an ordinary flow (bill a tenant, then change their rent), and no module test could
 * catch it: each module's suite drives one module, and neither knows the other exists.
 */
@Testcontainers
class SharedTenancyIdTest {

    /**
     * Every PM write names its workspace explicitly (rule 7): the header is what the caller is
     * checked against, so a write can no longer land in an agency nobody named.
     */
    private static final String DEV_WORKSPACE = "00000000-0000-0000-0000-000000000001";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static ConfigurableApplicationContext app;

    @BeforeAll
    static void start() {
        app = new SpringApplicationBuilder(NajemApplication.class).run(
            "--server.port=0",
            // Every deployment names its bank: the fake one is opt-in and its flag has no
            // default, so without these the app has no BankStatementPort and refuses to
            // start. This suite never calls the bank; it only has to name one.
            "--najem.bank.fake.enabled=true",
            "--najem.bank.base-url=http://localhost:8081",
            // Explicit: rule 7 forbids acquiring permit-all by omission.
            "--najem.security.permit-all=true",
            "--spring.datasource.url=" + pg.getJdbcUrl(),
            "--spring.datasource.username=" + pg.getUsername(),
            "--spring.datasource.password=" + pg.getPassword(),
            "--spring.flyway.locations=classpath:db/eventstore,classpath:db/pm,classpath:db/acc");
        RestAssured.port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @Test
    void pmKeepsWorkingOnATenancyAccountingHasCharged() {
        String propertyId = given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON)
            .body(Map.of("address", "Zbiegła 4, Wrocław"))
            .post("/api/pm/properties").then().statusCode(200).extract().path("propertyId");
        String unitId = given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON)
            .body(Map.of("name", "M3", "baseRent", "3100"))
            .post("/api/pm/properties/" + propertyId + "/units").then().statusCode(200).extract().path("unitId");
        String tenancyId = given().header("X-Workspace-Id", DEV_WORKSPACE)
            .contentType(ContentType.JSON)
            .body(Map.of("unitId", unitId,
                "tenantContactIds", List.of(UUID.randomUUID().toString()),
                "startDate", "2026-09-01", "endDate", "2027-08-31",
                "legalForm", "zwykly", "monthlyTotal", "3100",
                "paymentReference", "NAJEM/M3/2026"))
            .post("/api/pm/tenancies").then().statusCode(200).extract().path("tenancyId");
        given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON)
            .body(Map.of("activatedOn", "2026-09-01"))
            .post("/api/pm/tenancies/" + tenancyId + "/activate").then().statusCode(200);

        // Activation reaches accounting through the outbox, which posts the first charge against
        // the SAME id under stream type TenancyLedger. Wait for it: the collision only exists once
        // accounting has actually written, so asserting before that would pass either way.
        await().atMost(Duration.ofSeconds(10)).until(() -> !given().get("/api/acc/board")
            .then().statusCode(200).extract().jsonPath().getList("").isEmpty());

        given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON)
            .body(Map.of("decidedOn", "2026-09-15", "effectiveFrom", "2027-01-01",
                "monthlyTotal", "3300", "changeType", "agreed-change"))
            .post("/api/pm/tenancies/" + tenancyId + "/rent-changes").then().statusCode(200);

        given().header("X-Workspace-Id", DEV_WORKSPACE).contentType(ContentType.JSON)
            .body(Map.of("endDate", "2027-08-31", "vacateDate", "2027-08-31", "reasonType", "agreement-expiry"))
            .post("/api/pm/tenancies/" + tenancyId + "/end").then().statusCode(200);
    }
}
