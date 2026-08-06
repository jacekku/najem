package pl.najem.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenancy arc over HTTP, following §6 of the domain model: a caller asks about a flat, the
 * agreement is signed, the keys change hands, the tenancy runs, and the flat comes back to
 * market. Plus the one hard invariant, which is the only thing in this system that says no.
 */
@Testcontainers
@Tag("integration")
class TenancyLifecycleTest {

    /** Writes name their workspace explicitly (rule 7); children inherit it from the property. */
    private static final String WORKSPACE = "00000000-0000-0000-0000-000000000042";

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
            "--najem.bank.iban=PL61109010140000071219812874",
            // Explicit: rule 7 forbids acquiring permit-all by omission.
            "--najem.security.permit-all=true",
            // The one place X-Workspace-Id still works. This suite drives a real application
            // over HTTP with no identity provider in front of it, so there is no token, no
            // subject and no membership to resolve a workspace from. Explicit, because a
            // deployment must never acquire the header path by omission.
            "--najem.test.workspace-header=true",
            "--spring.datasource.url=" + pg.getJdbcUrl(),
            "--spring.datasource.username=" + pg.getUsername(),
            "--spring.datasource.password=" + pg.getPassword(),
            // Opt-in, never on by omission: the process-runner endpoint does not exist without it.
            "--najem.pm.test-endpoints.enabled=true",
            "--spring.flyway.locations=classpath:db/eventstore,classpath:db/pm,classpath:db/acc");
        RestAssured.port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @Test
    void fromSignedAgreementToMovedInTenantAndBackToMarket() {
        String unitId = openUnit("Testowa 1, Kraków", "M12", "2600");

        String tenancyId = reserve(unitId, "2026-09-01", "2027-08-31", "zwykly", "2500", null);

        as().body(Map.of("key", "keys", "phase", "pre-activation"))
            .post("/api/pm/tenancies/" + tenancyId + "/checklist").then().statusCode(200);

        // The keys are not handed over yet, so the start process leaves it reserved and stays
        // armed rather than cancelling anything. Ending-soon lists ACTIVE tenancies only.
        runProcesses("2026-09-01");
        assertThat(endingSoonIds("2027-08-01")).doesNotContain(tenancyId);

        as().post("/api/pm/tenancies/" + tenancyId + "/checklist/keys/complete")
            .then().statusCode(200);
        runProcesses("2026-09-04");

        // Activated late, and now a month before the agreed end it is on the attention list.
        assertThat(endingSoonIds("2027-08-01")).contains(tenancyId);
        assertThat(endingSoonIds("2027-06-01")).doesNotContain(tenancyId);

        as().body(Map.of("type", "end-of-tenancy",
                "meterReadings", List.of(Map.of("meterId", "cw-1", "utility", "cold-water",
                    "reading", "189.5")),
                "conditionNotes", "scuffed wall", "date", "2027-09-02"))
            .post("/api/pm/tenancies/" + tenancyId + "/handover").then().statusCode(200);

        as().body(Map.of("endDate", "2027-08-31", "vacateDate", "2027-09-02",
                "reasonType", "agreement-expiry", "backToMarket", true))
            .post("/api/pm/tenancies/" + tenancyId + "/end").then().statusCode(200);

        // Off the list, and the unit's calendar is free for the next tenant.
        assertThat(endingSoonIds("2027-08-01")).doesNotContain(tenancyId);
        reserve(unitId, "2027-10-01", "2028-09-30", "zwykly", "2700", null);
    }

    /** The one hard invariant. Everything else in this system warns; this refuses. */
    @Test
    void doubleBookingTheSameUnitIsRejected() {
        String unitId = openUnit("Zajęta 2", "M1", "2600");
        reserve(unitId, "2026-01-01", "2026-06-30", "zwykly", "2500", null);

        as().body(reservation(unitId, "2026-03-01", "2026-09-30", "zwykly", "2500", null))
            .post("/api/pm/tenancies").then().statusCode(409);
    }

    /** An overlap is a conflict with reality, not a malformed request — and it says which. */
    @Test
    void theconflictNamesTheTenancyThatAlreadyHoldsTheUnit() {
        String unitId = openUnit("Wyjaśniona 3", "M1", "2600");
        String first = reserve(unitId, "2026-01-01", "2026-06-30", "zwykly", "2500", null);

        String error = as().body(reservation(unitId, "2026-03-01", "2026-09-30", "zwykly",
                "2500", null))
            .post("/api/pm/tenancies").then().statusCode(409).extract().path("error");

        assertThat(error).contains(first);
    }

    /**
     * The expert-system stance over HTTP: an unlawfully short unilateral increase is accepted —
     * a manager who knows better is never blocked — and the statutory flag comes back with it.
     */
    @Test
    void anunlawfulRentIncreaseIsAcceptedAndFlagged() {
        String unitId = openUnit("Podwyżkowa 4", "M1", "2600");
        String tenancyId = reserve(unitId, "2026-09-01", "2028-08-31", "zwykly", "2500", null);
        as().body(Map.of("activatedOn", "2026-09-01"))
            .post("/api/pm/tenancies/" + tenancyId + "/activate").then().statusCode(200);

        List<String> warnings = as().body(Map.of("decidedOn", "2027-05-01",
                "effectiveFrom", "2027-06-01", "changeType", "unilateral-increase",
                "monthlyTotal", "2700"))
            .post("/api/pm/tenancies/" + tenancyId + "/rent-changes")
            .then().statusCode(200).extract().path("warnings");

        assertThat(warnings).anyMatch(w -> w.contains("3 months"));
    }

    /** A deposit over the statutory cap warns rather than refusing — and names the cap. */
    @Test
    void adepositOverTheStatutoryCapIsFlaggedNotRefused() {
        String unitId = openUnit("Kaucyjna 5", "M1", "2600");

        List<String> warnings = as()
            .body(reservation(unitId, "2026-09-01", "2027-08-31", "instytucjonalny", "2500",
                "20000"))
            .post("/api/pm/tenancies").then().statusCode(200).extract().path("warnings");

        assertThat(warnings).anyMatch(w -> w.contains("Deposit"));
    }

    /** Rule 7 over the wire: an omitted legal form is refused, not quietly made zwykły. */
    @Test
    void areservationWithNoLegalFormIsRefused() {
        String unitId = openUnit("Bezformowa 6", "M1", "2600");

        as().body(reservation(unitId, "2026-09-01", "2027-08-31", null, "2500", null))
            .post("/api/pm/tenancies").then().statusCode(400);
    }

    /** Rule 7 again: a property write that names no workspace is refused, not defaulted. */
    @Test
    void apropertyWriteWithoutAWorkspaceIsRefused() {
        given().contentType(ContentType.JSON).body(Map.of("address", "Bezimienna 7"))
            .post("/api/pm/properties").then().statusCode(400);
    }

    // --- helpers ---

    private static RequestSpecification as() {
        return given().header("X-Workspace-Id", WORKSPACE).contentType(ContentType.JSON);
    }

    private static String openUnit(String address, String name, String baseRent) {
        String propertyId = as().body(Map.of("address", address))
            .post("/api/pm/properties").then().statusCode(200).extract().path("propertyId");
        String unitId = as().body(Map.of("name", name, "baseRent", baseRent))
            .post("/api/pm/properties/" + propertyId + "/units")
            .then().statusCode(200).extract().path("unitId");
        as().post("/api/pm/units/" + unitId + "/open").then().statusCode(200);
        return unitId;
    }

    private static Map<String, Object> reservation(String unitId, String start, String end,
                                                   String legalForm, String monthlyTotal,
                                                   String deposit) {
        var body = new java.util.HashMap<String, Object>(Map.of(
            "unitId", unitId,
            "tenantContactIds", List.of(UUID.randomUUID().toString()),
            "startDate", start, "endDate", end,
            "monthlyTotal", monthlyTotal,
            "paymentReference", "NAJEM/" + UUID.randomUUID()));
        if (legalForm != null) {
            body.put("legalForm", legalForm);
        }
        if (deposit != null) {
            body.put("depositAmount", deposit);
        }
        return body;
    }

    private static String reserve(String unitId, String start, String end, String legalForm,
                                  String monthlyTotal, String deposit) {
        return as().body(reservation(unitId, start, end, legalForm, monthlyTotal, deposit))
            .post("/api/pm/tenancies").then().statusCode(200).extract().path("tenancyId");
    }

    private static void runProcesses(String on) {
        as().post("/api/pm/processes/run?on=" + on).then().statusCode(200);
    }

    private static List<String> endingSoonIds(String on) {
        return as().get("/api/pm/attention/ending-soon?on=" + on)
            .then().statusCode(200).extract().path("tenancyId");
    }
}
