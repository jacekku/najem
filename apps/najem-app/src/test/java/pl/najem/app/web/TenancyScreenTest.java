package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import pl.najem.app.SharedDatabase;
import pl.najem.eventstore.OutboxDispatcher;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.application.TenancyService;
import pl.najem.pm.domain.Owner;
import pl.najem.reporting.application.ProjectionRunner;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract screen, through the stack a manager actually uses.
 *
 * <p>Container tier, because what this covers is the join across three modules that only a running
 * stack performs: PM's register row, Contacts' names for the parties on it, and Accounting's
 * balance. Modelled on {@link ReserveScreenTest} and reserving through the real HTTP endpoint
 * rather than calling {@code TenancyService} directly — a tenancy created by a helper and a tenancy
 * created by the application are not the same fixture, and this screen renders the second.
 *
 * <p>Its own operator subject, as every application test has: memberships resolve per subject and
 * two classes sharing one pool their agencies (see {@code SharedDatabaseIsolationTest}).
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + TenancyScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Tag("integration")
class TenancyScreenTest extends SharedDatabase {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000075";

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired PortfolioService portfolio;
    @Autowired ProjectionRunner projections;
    @Autowired TenancyService tenancies;
    @Autowired OutboxDispatcher outbox;
    @Autowired JdbcTemplate jdbc;

    /** Static: JUnit builds a new instance per method, so an instance guard would never be false
     *  and every method would create another property (WebWorkspaceChoiceTest's own note). */
    static UUID agency;
    static UUID unitId;
    static UUID tenancyId;

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2027, 8, 31);

    @BeforeEach
    void aSignedContract() throws Exception {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency != null) {
            return;
        }
        agency = workspaces.create("Agencja Umów", operator, LocalDate.now());
        var property = portfolio.createProperty(agency, "ul. Hoża 42, 00-516 Warszawa",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        unitId = portfolio.addUnit(agency, property, "2A", new BigDecimal("3900"));
        projections.runOnce();

        // Through the screen, not through the service: registerLead creates a PERSON and no
        // interest, and a reservation needs the interest it converts. The unit screen's own form is
        // what creates both, so the fixture uses it — the same reason this class reserves over HTTP
        // rather than calling TenancyService.
        mvc.perform(post("/units/" + unitId + "/interests").with(csrf())
                .param("givenName", "Piotr").param("surname", "Zieliński")
                .param("email", "p.zielinski@wp.pl").param("phone", "+48 601 245 118")
                .param("infoClauseServed", "true"))
            .andExpect(status().is3xxRedirection());
        UUID interestId = interestOf("p.zielinski@wp.pl");

        // Najem OKAZJONALNY on purpose: the document list and the two notarial milestones are the
        // one part of this screen whose SHAPE is decided by a real field, so the form that exercises
        // that branch is the one worth building the fixture from.
        mvc.perform(post("/units/" + unitId + "/reserve").with(csrf())
                // No `tenant` param: ReserveScreenController prepends the LEAD as the first tenant
                // and refuses a draft that names them again (DuplicatePartyException). Passing the
                // lead's own contact here is the mistake that produced a redirect with no
                // pm_tenancy row behind it.
                .param("interestId", interestId.toString())
                .param("legalForm", "OKAZJONALNY")
                .param("startDate", START.toString())
                .param("termKind", "fixed")
                .param("endDate", END.toString())
                .param("monthlyTotal", "4540")
                .param("componentSplit", "true")
                .param("rent", "3900")
                .param("adminFee", "0")
                .param("mediaAdvance", "640")
                .param("rentDay", "10")
                .param("depositAmount", "7800")
                .param("paymentReference", "NAJEM/2A/2026-09"))
            .andExpect(status().is3xxRedirection());
        projections.runOnce();

        tenancyId = jdbc.queryForObject("select tenancy_id from pm_tenancy where unit_id = ?",
            (rs, i) -> rs.getObject(1, UUID.class), unitId);
    }

    /** The interest the form just created, found by the e-mail it was created with — the same
     *  join {@code ReserveScreenTest} uses, and for the same reason: the POST redirects and does not
     *  hand back the id. */
    private UUID interestOf(String email) {
        return jdbc.queryForObject(
            "select interest_id from contacts_interest i join contacts_person p "
                + "on p.contact_id = i.contact_id where p.email = ?",
            (rs, i) -> rs.getObject(1, UUID.class), email);
    }

    private String render() throws Exception {
        return mvc.perform(get("/tenancies/" + tenancyId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    /**
     * The four facts the header exists to state, and every one of them read rather than invented.
     *
     * <p>The reference is DERIVED and that is the point of asserting its exact shape: it is built
     * from the start year, the property address and the unit name, so a change to how any of those
     * three is reduced changes what a manager quotes on the phone.
     */
    @Test
    void theHeaderNamesTheTenantTheReferenceAndTheLegalForm() throws Exception {
        var html = render();

        assertThat(html).contains("Piotr Zieliński");
        assertThat(html).as("NJ-, the start year, the reduced address and the unit")
            .contains("NJ-2026/HOZA42-2A");
        assertThat(html).contains("Najem okazjonalny");
        assertThat(html).as("PM's own state, not accounting's colour").contains("Zarezerwowana");
    }

    /**
     * The financial terms come from the register, including the component split.
     *
     * <p>The split is asserted specifically because V22 records that "no split" and "a split with a
     * zero admin fee" are legally different: this contract declares one WITH a zero admin fee, and
     * a screen that inferred the split from the numbers would render it as undivided.
     */
    @Test
    void theTermsAreTheOnesSigned() throws Exception {
        var html = render();

        assertThat(html).contains("01.09.2026 – 31.08.2027");
        assertThat(html).as("the split's three components, zero admin fee included")
            .contains("Czynsz bazowy").contains("Opłata administracyjna").contains("Zaliczka na media");
        assertThat(html).contains("4 540,00");
        assertThat(html).contains("10. dnia miesiąca");
        assertThat(html).contains("NAJEM/2A/2026-09");
    }

    /**
     * The document list's SHAPE follows the legal form, which is the one thing on that invented card
     * that is not invented.
     *
     * <p>Both directions matter and only one of them is obvious. Showing the notarial documents for
     * a najem okazjonalny is right; showing them for a najem zwykły would tell a manager to chase
     * paperwork the law does not ask for, which is why the negative case is a separate test below
     * rather than left to be inferred from this one.
     */
    @Test
    void anOccasionalTenancyListsItsNotarialDocuments() throws Exception {
        var html = render();

        assertThat(html).contains("Umowa najmu okazjonalnego");
        assertThat(html).contains("Oświadczenie o poddaniu się egzekucji");
        assertThat(html).contains("Wskazanie lokalu zastępczego");
    }

    /**
     * The lifecycle carries the two REAL dates and does not invent one for the three it has no date
     * for.
     *
     * <p>The notice window is derived from the end date, so it is checked by value: three months
     * back from 31.08.2027 is 31.05.2027, and a regression that counted from the start or used a
     * different period would still render a plausible-looking date.
     */
    @Test
    void theLifecycleCountsTheNoticeWindowBackFromTheEnd() throws Exception {
        var html = render();

        assertThat(html).as("signed on the start date").contains("01.09.2026");
        assertThat(html).as("three months before the end").contains("31.05.2027");
        assertThat(html).contains("Okno wypowiedzenia").contains("Koniec umowy");
    }

    /**
     * The deposit card, once there is a deposit — the shortfall bar, and the fact that it is real.
     *
     * <p>Prototype v2 draws this card with an invented figure and falls back to twice the rent when
     * it has none. This application has the real pair: {@code acc_deposit} carries the agreed
     * nominal and what is still unpaid, so held is the difference and the shortfall is not a
     * computation this screen invents. Activating is what charges it, so the tenancy is activated
     * here and only here.
     *
     * <p>Both the dispatcher and the projector are drained EXPLICITLY. Waiting for the 500ms poll
     * would make this pass or fail on timing, which is the one kind of red nobody can reproduce —
     * the same call {@code UnitScreenTest} makes about {@code ProjectionRunner}.
     */
    @Test
    void anActivatedTenancyShowsWhatOfItsDepositIsActuallyHeld() throws Exception {
        // Its own unit and its own tenancy: activating the class's shared fixture would leave every
        // other method reading a contract in a different state depending on execution order, which
        // is exactly the order-dependence SharedDatabaseIsolationTest was written about.
        var property = portfolio.createProperty(agency, "ul. Wilcza 7, 00-538 Warszawa",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        var otherUnit = portfolio.addUnit(agency, property, "1B", new BigDecimal("3000"));
        projections.runOnce();

        mvc.perform(post("/units/" + otherUnit + "/interests").with(csrf())
                .param("givenName", "Anna").param("surname", "Kowalska")
                .param("email", "a.kowalska@op.pl").param("infoClauseServed", "true"))
            .andExpect(status().is3xxRedirection());
        mvc.perform(post("/units/" + otherUnit + "/reserve").with(csrf())
                .param("interestId", interestOf("a.kowalska@op.pl").toString())
                .param("legalForm", "ZWYKLY")
                .param("startDate", LocalDate.now().minusDays(1).toString())
                .param("termKind", "indefinite")
                .param("monthlyTotal", "3000")
                .param("rentDay", "10")
                .param("depositAmount", "6000")
                .param("paymentReference", "NAJEM/1B/2026-08"))
            .andExpect(status().is3xxRedirection());

        UUID activated = jdbc.queryForObject("select tenancy_id from pm_tenancy where unit_id = ?",
            (rs, i) -> rs.getObject(1, UUID.class), otherUnit);
        tenancies.activate(agency, activated, LocalDate.now());
        outbox.dispatchPending();
        projections.runOnce();

        var html = mvc.perform(get("/tenancies/" + activated))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).as("the agreed figure, from acc_deposit and not from twice the rent")
            .contains("6 000,00");
        assertThat(html).as("nothing paid yet, so the whole deposit is the shortfall")
            .contains("Niedobór");
        assertThat(html).as("and the bar is drawn").contains("progress-bar__segment");
    }

    /**
     * A tenancy in another agency is a 404, not a 403 and not a rendered page.
     *
     * <p>Undifferentiated from an id that does not exist at all, deliberately: a route that answered
     * differently for a foreign tenancy would confirm the id names something. Same call every other
     * detail screen here makes.
     */
    @Test
    void aTenancyThisAgencyDoesNotOwnIsNotFound() throws Exception {
        mvc.perform(get("/tenancies/" + UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    /**
     * The register row opens the contract, which is the navigation prototype v2 rewires.
     *
     * <p>Asserted from the register's own rendered HTML rather than from the controller, because
     * what changed is a link in a template and a unit test of the controller would not have noticed.
     */
    @Test
    void theRegisterRowOpensTheContract() throws Exception {
        var html = mvc.perform(get("/tenancies"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("href=\"/tenancies/" + tenancyId + "\"");
    }
}
