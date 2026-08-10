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
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactService;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.TenancyEvents;
import pl.najem.reporting.application.ProjectionRunner;
import pl.najem.reporting.application.UnitBoardQuery;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reservation screen through the stack a manager actually uses, from a lead's interest to a
 * signed {@code pm_tenancy} row and the conversion the interest records.
 *
 * <p>Container tier, because the three things this covers are all things only a running stack
 * decides: the join between phase 1's drafted parties and phase 2's terms, the overlap refusal
 * against the real unique-period constraint, and the cancel undo. Modelled on
 * {@link UnitScreenTest}: real Postgres via {@code @ServiceConnection}, {@code permit-all}, and
 * projections drained explicitly rather than waited for.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + ReserveScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Tag("integration")
class ReserveScreenTest extends SharedDatabase {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000020";

    /** Pinned independently of {@code ReserveScreenController.DATE}: a wrong pattern in the
     *  controller must fail this test, not silently agree with itself. */
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired PortfolioService portfolio;
    @Autowired ProjectionRunner projections;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventStore store;
    @Autowired UnitBoardQuery units;
    @Autowired ContactService contactService;

    /** Static: the agency and its property are shared fixture, same reasoning as UnitScreenTest. */
    static UUID agency;
    static UUID propertyId;

    /** Instance: a fresh unit per test, so one test's reservation never collides with another's. */
    UUID unitId;

    @BeforeEach
    void anAgencyAndAFreshUnit() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency == null) {
            agency = workspaces.create("Agencja Rezerwacji", operator, LocalDate.now());
            propertyId = portfolio.createProperty(agency, "ul. Rezerwowa 1, 00-002 Warszawa",
                List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        }
        unitId = portfolio.addUnit(agency, propertyId, "m. " + UUID.randomUUID(), new BigDecimal("2850"));
        projections.runOnce();
    }

    /** Registers a lead through the real interest screen and returns their interest id. */
    private UUID lead(String givenName, String surname, String email) throws Exception {
        mvc.perform(post("/units/" + unitId + "/interests").with(csrf())
                .param("givenName", givenName).param("surname", surname)
                .param("email", email).param("infoClauseServed", "true"))
            .andExpect(status().is3xxRedirection());
        return (UUID) jdbc.queryForObject(
            "select interest_id from contacts_interest i join contacts_person p "
                + "on p.contact_id = i.contact_id where p.email = ?",
            (rs, i) -> rs.getObject(1, UUID.class), email);
    }

    private void reserveTerms(UUID interestId, List<String> tenants, List<String> guarantors,
                              LocalDate startDate, String paymentReference) throws Exception {
        var request = post("/units/" + unitId + "/reserve").with(csrf())
            .param("interestId", interestId.toString())
            .param("legalForm", "ZWYKLY")
            .param("startDate", startDate.toString())
            .param("termKind", "indefinite")
            .param("monthlyTotal", "2850")
            .param("rentDay", "10")
            .param("paymentReference", paymentReference);
        for (String tenant : tenants) {
            request = request.param("tenant", tenant);
        }
        for (String guarantor : guarantors) {
            request = request.param("guarantor", guarantor);
        }
        mvc.perform(request).andExpect(status().is3xxRedirection());
    }

    /** A blocker with a real end date, which is what the free-up assertions need to have one. */
    private void reserveFixedTerm(UUID interestId, LocalDate startDate, LocalDate endDate,
                                  String paymentReference) throws Exception {
        mvc.perform(post("/units/" + unitId + "/reserve").with(csrf())
                .param("interestId", interestId.toString())
                .param("legalForm", "ZWYKLY")
                .param("startDate", startDate.toString())
                .param("termKind", "fixed")
                .param("endDate", endDate.toString())
                .param("monthlyTotal", "2850")
                .param("rentDay", "10")
                .param("paymentReference", paymentReference))
            .andExpect(status().is3xxRedirection());
    }

    /**
     * The parties screen shows the person picker exactly once, under whichever role is being added
     * to.
     *
     * <p>Counting rather than asserting presence, because presence is what every other assertion
     * here already checks and it is what missed this: {@code partiesForm} was declared as a
     * {@code th:fragment} inside {@code reserve-parties.html}, and an inline fragment is part of its
     * own document — so Thymeleaf rendered it where it sat as well as where it was inserted, and the
     * screen carried a second, unlabelled picker below the Dalej button. Every test passed. Found by
     * opening the page.
     */
    @Test
    void thePartiesScreenShowsOnePersonPicker() throws Exception {
        UUID interestId = lead("Zofia", "Dąbrowska", "zofia.dabrowska@example.com");

        String body = mvc.perform(get("/units/" + unitId + "/reserve")
                .param("interestId", interestId.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body.split("— albo nowa osoba —", -1).length - 1).isEqualTo(1);
    }

    /**
     * The parties screen renders with somebody actually drafted, and offers to remove them.
     *
     * <p>Every other test here rendered this screen empty, and that is what let a broken Usuń link
     * ship: it computed the remaining ids with a SpringEL selection, which rebinds the root object
     * to each element, so {@code party} resolved against a {@code UUID} and the page threw the
     * moment anybody was in the list. An empty list has nothing to iterate, so the expression was
     * never evaluated and every assertion passed. Found by adding a guarantor by hand.
     */
    @Test
    void thePartiesScreenRendersWithSomebodyDraftedAndOffersToRemoveThem() throws Exception {
        UUID interestId = lead("Halina", "Baran", "halina.baran@example.com");
        UUID guarantorId = contactService.registerParty(agency,
            new ContactDetails("Ryszard", "Baran", "ryszard.baran@example.com", "+48 500 111 222"),
            true, LocalDate.now());

        mvc.perform(get("/units/" + unitId + "/reserve")
                .param("interestId", interestId.toString())
                .param("guarantor", guarantorId.toString())
                .param("role", "guarantor"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Ryszard Baran")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Usuń")));
    }

    @Test
    void reservingFromALeadCreatesTheTenancyAndClosesTheInterest() throws Exception {
        UUID interestId = lead("Anna", "Kowalska", "anna.kowalska@example.com");
        projections.runOnce();

        reserveTerms(interestId, List.of(), List.of(),
            LocalDate.now().plusDays(1), "NAJEM/KOWALSKA/09");

        var tenancyRow = jdbc.queryForMap(
            "select tenancy_id, state, payment_reference from pm_tenancy where unit_id = ?", unitId);
        assertThat(tenancyRow.get("state")).isEqualTo("RESERVED");
        assertThat(tenancyRow.get("payment_reference")).isEqualTo("NAJEM/KOWALSKA/09");
        assertThat(jdbc.queryForObject(
            "select count(*) from pm_tenancy where unit_id = ?", Integer.class, unitId))
            .isEqualTo(1);

        UUID tenancyId = (UUID) tenancyRow.get("tenancy_id");
        var interestRow = jdbc.queryForMap(
            "select status, converted_to_tenancy_id from contacts_interest where interest_id = ?",
            interestId);
        assertThat(interestRow.get("status")).isEqualTo("converted");
        assertThat(interestRow.get("converted_to_tenancy_id")).isEqualTo(tenancyId);

        mvc.perform(get("/units/" + unitId))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Kowalska"))));
    }

    @Test
    void aGuarantorCreatedOnTheFormIsHeldUnderContract() throws Exception {
        UUID interestId = lead("Piotr", "Zawadzki", "piotr.zawadzki@example.com");

        mvc.perform(post("/units/" + unitId + "/reserve/parties").with(csrf())
                .param("interestId", interestId.toString())
                .param("role", "guarantor")
                .param("givenName", "Ewa").param("surname", "Poreczycielka")
                .param("email", "ewa.poreczycielka@example.com").param("phone", "+48500000001")
                .param("infoClauseServed", "true"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        UUID guarantorId = (UUID) jdbc.queryForObject(
            "select contact_id from contacts_person where email = ?",
            (rs, i) -> rs.getObject(1, UUID.class), "ewa.poreczycielka@example.com");

        reserveTerms(interestId, List.of(), List.of(guarantorId.toString()),
            LocalDate.now().plusDays(1), "NAJEM/ZAWADZKI/09");

        var guarantorRow = jdbc.queryForMap(
            "select lawful_basis from contacts_person where contact_id = ?", guarantorId);
        assertThat(guarantorRow.get("lawful_basis")).isEqualTo("contract");

        UUID tenancyId = (UUID) jdbc.queryForObject(
            "select tenancy_id from pm_tenancy where unit_id = ?",
            (rs, i) -> rs.getObject(1, UUID.class), unitId);
        var reserved = store.load(tenancyId, "Tenancy").events().stream()
            .filter(TenancyEvents.TenancyReserved.class::isInstance)
            .map(TenancyEvents.TenancyReserved.class::cast)
            .findFirst().orElseThrow();
        assertThat(reserved.guarantorContactIds()).contains(guarantorId);
    }

    @Test
    void theLeadsLawfulBasisBecomesContract() throws Exception {
        UUID interestId = lead("Marek", "Lewandowski", "marek.lewandowski@example.com");

        var before = jdbc.queryForObject(
            "select lawful_basis from contacts_person where email = ?",
            String.class, "marek.lewandowski@example.com");
        assertThat(before).isEqualTo("legitimate-interest");

        reserveTerms(interestId, List.of(), List.of(),
            LocalDate.now().plusDays(1), "NAJEM/LEWANDOWSKI/09");

        var after = jdbc.queryForObject(
            "select lawful_basis from contacts_person where email = ?",
            String.class, "marek.lewandowski@example.com");
        assertThat(after).isEqualTo("contract");
    }

    @Test
    void reservingOverAnExistingTenancyRendersTheErrorAndCreatesNothing() throws Exception {
        UUID firstInterestId = lead("Jan", "Wysocki", "jan.wysocki@example.com");
        LocalDate startDate = LocalDate.now().plusDays(1);
        reserveTerms(firstInterestId, List.of(), List.of(), startDate, "NAJEM/WYSOCKI/09");

        UUID secondInterestId = lead("Karol", "Nowicki", "karol.nowicki@example.com");

        int tenanciesBefore = jdbc.queryForObject(
            "select count(*) from pm_tenancy where unit_id = ?", Integer.class, unitId);
        int convertedInterestsBefore = jdbc.queryForObject(
            "select count(*) from contacts_interest where status = 'converted'", Integer.class);
        int contractPartiesBefore = jdbc.queryForObject(
            "select count(*) from contacts_person where lawful_basis = 'contract'", Integer.class);

        var request = post("/units/" + unitId + "/reserve").with(csrf())
            .param("interestId", secondInterestId.toString())
            .param("legalForm", "ZWYKLY")
            .param("startDate", startDate.plusDays(5).toString())
            .param("termKind", "indefinite")
            .param("monthlyTotal", "2850")
            .param("rentDay", "10")
            .param("paymentReference", "NAJEM/NOWICKI/09");

        mvc.perform(request)
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .model().attributeExists("error"));

        assertThat(jdbc.queryForObject(
            "select count(*) from pm_tenancy where unit_id = ?", Integer.class, unitId))
            .isEqualTo(tenanciesBefore);
        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_interest where status = 'converted'", Integer.class))
            .isEqualTo(convertedInterestsBefore);
        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_person where lawful_basis = 'contract'", Integer.class))
            .isEqualTo(contractPartiesBefore);
    }

    /**
     * The refusal says when the unit frees up, and says it about the period that BLOCKS.
     *
     * <p>It used to describe the dates the manager had just typed, which was not true — the unit is
     * not reserved for the period they asked for, it is reserved for somebody else's. Asserting the
     * blocking tenancy's own end date is what tells the two apart: the submitted start here is
     * deliberately different from the blocker's, so a message built from the wrong one cannot pass.
     *
     * <p>The freed-up date is the blocker's end itself, not the day after. Periods are half-open, so
     * a tenancy ending on the 30th leaves the 30th open — a message that said the 31st would cost
     * the agency a day of rent on every back-to-back letting.
     */
    @Test
    void therefusalNamesTheBlockingPeriodAndWhenItFreesUp() throws Exception {
        UUID firstInterestId = lead("Wanda", "Sikora", "wanda.sikora@example.com");
        LocalDate blockerStart = LocalDate.now().plusDays(1);
        LocalDate blockerEnd = blockerStart.plusMonths(6);
        reserveFixedTerm(firstInterestId, blockerStart, blockerEnd, "NAJEM/SIKORA/09");

        UUID secondInterestId = lead("Roman", "Duda", "roman.duda@example.com");

        mvc.perform(post("/units/" + unitId + "/reserve").with(csrf())
                .param("interestId", secondInterestId.toString())
                .param("legalForm", "ZWYKLY")
                .param("startDate", blockerStart.plusDays(30).toString())
                .param("termKind", "indefinite")
                .param("monthlyTotal", "2850")
                .param("rentDay", "10")
                .param("paymentReference", "NAJEM/DUDA/09"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Lokal jest już zarezerwowany od " + DATE.format(blockerStart) + " do " + DATE.format(blockerEnd))))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Zwolni się " + DATE.format(blockerEnd))));
    }

    /** An indefinite blocker never frees up, and the screen has to say that rather than show a gap. */
    @Test
    void therefusalSaysAnIndefiniteBlockerNeverFreesUp() throws Exception {
        UUID firstInterestId = lead("Irena", "Mazur", "irena.mazur@example.com");
        LocalDate blockerStart = LocalDate.now().plusDays(1);
        reserveTerms(firstInterestId, List.of(), List.of(), blockerStart, "NAJEM/MAZUR/09");

        UUID secondInterestId = lead("Filip", "Zych", "filip.zych@example.com");

        mvc.perform(post("/units/" + unitId + "/reserve").with(csrf())
                .param("interestId", secondInterestId.toString())
                .param("legalForm", "ZWYKLY")
                .param("startDate", blockerStart.plusYears(3).toString())
                .param("termKind", "indefinite")
                .param("monthlyTotal", "2850")
                .param("rentDay", "10")
                .param("paymentReference", "NAJEM/ZYCH/09"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Lokal jest już zajęty bezterminowo od " + DATE.format(blockerStart))))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "nie zwolni się")));
    }

    /**
     * The terms screen shows the unit's taken dates BEFORE the manager types, which is the point of
     * having them there at all — the error below the fields explains a clash, this prevents one.
     *
     * <p>Asserted both ways round: a unit with nothing booked must say so rather than render an
     * empty box, because an empty box and a unit that is free look identical and only one of them
     * is an answer.
     */
    @Test
    void thetermsScreenShowsTheUnitsBookedDatesBeforeAnythingIsTyped() throws Exception {
        UUID firstInterestId = lead("Bogdan", "Krupa", "bogdan.krupa@example.com");
        UUID secondInterestId = lead("Lidia", "Cichy", "lidia.cichy@example.com");

        mvc.perform(get("/units/" + unitId + "/reserve")
                .param("interestId", secondInterestId.toString())
                .param("parties", "done"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Lokal nie ma jeszcze żadnych rezerwacji")));

        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = start.plusMonths(6);
        reserveFixedTerm(firstInterestId, start, end, "NAJEM/KRUPA/09");

        mvc.perform(get("/units/" + unitId + "/reserve")
                .param("interestId", secondInterestId.toString())
                .param("parties", "done"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "zarezerwowany od " + DATE.format(start) + " do " + DATE.format(end))))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("wolny od " + DATE.format(end))));
    }

    /**
     * A guarantor id drafted while valid can go stale before this POST — another manager may have
     * erased the contact in between, or (as crafted here) it may simply belong to another workspace.
     * Must 404 before anything commits: no tenancy, no conversion, no lawful-basis change. Modelled
     * on {@link #reservingOverAnExistingTenancyRendersTheErrorAndCreatesNothing}, which already
     * captures counts before and compares after for the sibling failure mode.
     */
    @Test
    void reservingWithAGuarantorFromAnotherWorkspaceIsNotFoundAndCreatesNothing() throws Exception {
        UUID interestId = lead("Tomasz", "Grabowski", "tomasz.grabowski@example.com");

        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        UUID theirs = workspaces.create("Cudza agencja (rezerwacja)", stranger, LocalDate.now());
        UUID theirGuarantor = contactService.registerLead(theirs,
            new ContactDetails("Obcy", "Poreczyciel", "obcy.poreczyciel@example.com", null),
            true, LocalDate.now());

        int tenanciesBefore = jdbc.queryForObject(
            "select count(*) from pm_tenancy where unit_id = ?", Integer.class, unitId);
        int convertedInterestsBefore = jdbc.queryForObject(
            "select count(*) from contacts_interest where status = 'converted'", Integer.class);
        int contractPartiesBefore = jdbc.queryForObject(
            "select count(*) from contacts_person where lawful_basis = 'contract'", Integer.class);

        var request = post("/units/" + unitId + "/reserve").with(csrf())
            .param("interestId", interestId.toString())
            .param("guarantor", theirGuarantor.toString())
            .param("legalForm", "ZWYKLY")
            .param("startDate", LocalDate.now().plusDays(1).toString())
            .param("termKind", "indefinite")
            .param("monthlyTotal", "2850")
            .param("rentDay", "10")
            .param("paymentReference", "NAJEM/GRABOWSKI/09");

        mvc.perform(request).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
            "select count(*) from pm_tenancy where unit_id = ?", Integer.class, unitId))
            .isEqualTo(tenanciesBefore);
        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_interest where status = 'converted'", Integer.class))
            .isEqualTo(convertedInterestsBefore);
        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_person where lawful_basis = 'contract'", Integer.class))
            .isEqualTo(contractPartiesBefore);
    }

    /**
     * A tenancy id this workspace has never heard of must 404, the same undifferentiated answer a
     * foreign id gets, not the unmapped 500 an {@code UnknownInThisWorkspaceException} produced
     * before {@code WebErrorAdvice} learned to map it.
     */
    @Test
    void cancellingAnUnknownTenancyIsNotFound() throws Exception {
        mvc.perform(post("/tenancies/" + UUID.randomUUID() + "/cancel").with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    void cancellingAReservationFreesTheUnitAndRefusesASecondCancel() throws Exception {
        UUID interestId = lead("Zofia", "Baranowska", "zofia.baranowska@example.com");
        reserveTerms(interestId, List.of(), List.of(),
            LocalDate.now().plusDays(1), "NAJEM/BARANOWSKA/09");
        UUID tenancyId = (UUID) jdbc.queryForObject(
            "select tenancy_id from pm_tenancy where unit_id = ?",
            (rs, i) -> rs.getObject(1, UUID.class), unitId);
        projections.runOnce();

        // The reservation starts tomorrow, so the unit has no CURRENT tenancy either side of the
        // cancel — "· nadchodzący" (unit.html, rendered only when nextTenancyId != null) is the one
        // marker that flips, and is asserted both sides so a template that never rendered it could
        // not pass this test by accident.
        mvc.perform(get("/units/" + unitId))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("nadchodzący")));

        mvc.perform(post("/tenancies/" + tenancyId + "/cancel").with(csrf())
                .param("reason", "Manager mistake"))
            .andExpect(status().is3xxRedirection());
        projections.runOnce();

        mvc.perform(get("/units/" + unitId))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("nadchodzący"))));
        var freed = units.forUnit(agency, unitId, LocalDate.now()).orElseThrow();
        assertThat(freed.currentTenancyId()).isNull();
        assertThat(freed.nextTenancyId()).isNull();

        mvc.perform(post("/tenancies/" + tenancyId + "/cancel").with(csrf()))
            .andExpect(status().isConflict());
    }
}
