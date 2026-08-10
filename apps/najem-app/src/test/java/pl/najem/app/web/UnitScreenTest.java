package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.ContactService;
import pl.najem.contacts.application.UnitInterestQuery;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;
import pl.najem.reporting.application.ProjectionRunner;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scenario the screen exists for, through the stack a manager actually uses.
 *
 * <p>Container tier, because the three things this covers are all things only a running stack
 * decides: the join between the interest and the person, the redirect, and the CSRF filter.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + UnitScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class UnitScreenTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000010";

    /**
     * Independently declared, the way ReserveScreenTest's own DATE constant is — not calling the
     * production formatter and not pasting its output, so a revert of
     * UnitScreenController.InterestedPartyView.desiredStart to raw ISO yyyy-MM-dd (the module has
     * no thymeleaf-extras-java8time, so this formatting can only happen in Java) fails this
     * assertion instead of passing it vacuously.
     */
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired PortfolioService portfolio;
    @Autowired ProjectionRunner projections;
    @Autowired ContactDirectory directory;
    @Autowired ContactService contactService;
    @Autowired UnitInterestQuery interested;
    @Autowired JdbcTemplate jdbc;

    /** Static: JUnit builds a new instance per method, so an instance guard would never be false. */
    static UUID agency;
    static UUID unitId;

    @BeforeEach
    void aUnitToRingAbout() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency == null) {
            agency = workspaces.create("Agencja Zainteresowanych", operator, LocalDate.now());
            var property = portfolio.createProperty(agency, "ul. Testowa 1, 00-001 Warszawa",
                List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
            unitId = portfolio.addUnit(agency, property, "m. 2", new BigDecimal("2850"));
        }
        // Drained explicitly rather than waited for. The scheduled poll would make this pass or
        // fail on timing, which is the one kind of red nobody can reproduce.
        projections.runOnce();
    }

    private void ring(String... params) throws Exception {
        var request = post("/units/" + unitId + "/interests").with(csrf());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        mvc.perform(request)
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/units/" + unitId));
    }

    @Test
    void aleadPhonesAboutAUnitAndAppearsOnItsScreen() throws Exception {
        ring("givenName", "Piotr", "surname", "Nowak",
            "email", "p.nowak@example.com", "phone", "+48500000000",
            "infoClauseServed", "true",
            "willingToPay", "2900.00", "desiredStart", "2026-09-01");

        mvc.perform(get("/units/" + unitId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Piotr Nowak")))
            // The stated format, not the server locale's — see units.html.
            .andExpect(content().string(containsString("2 900,00")))
            // desiredStart is pre-formatted dd.MM.yyyy in InterestedPartyView (Java, not
            // Thymeleaf — see this class's DATE constant); rendered as text in unit.html's table,
            // not fed to an <input type="date">, so this is the format that must appear, not ISO.
            .andExpect(content().string(containsString(DATE.format(LocalDate.of(2026, 9, 1)))));
    }

    /** The basis is the module's decision, and this is where it becomes visible in a row. */
    @Test
    void aleadIsStoredUnderLegitimateInterestWithTheClauseTheManagerConfirmed() throws Exception {
        ring("givenName", "Marta", "surname", "Wisniewska",
            "email", "m.w@example.com", "infoClauseServed", "true");

        var row = jdbc.queryForMap(
            "select lawful_basis, info_clause_served_at from contacts_person where email = ?",
            "m.w@example.com");

        assertThat(row.get("lawful_basis")).isEqualTo("legitimate-interest");
        assertThat(row.get("info_clause_served_at")).isNotNull();
    }

    /** Unticked means unticked. Recording today anyway would be the application asserting it. */
    @Test
    void anunconfirmedClauseIsStoredAsAbsent() throws Exception {
        ring("givenName", "Jan", "surname", "Bezklauzuli", "email", "j.b@example.com");

        assertThat(jdbc.queryForObject(
            "select info_clause_served_at from contacts_person where email = ?",
            LocalDate.class, "j.b@example.com")).isNull();
    }

    /**
     * The point of searching before creating. A second interest under the picked contact must not
     * produce a second person: erasure deletes one row, and would leave the other behind.
     */
    @Test
    void pickingSomebodyWeKnowDoesNotCreateThemAgain() throws Exception {
        ring("givenName", "Tomasz", "surname", "Zielinski", "email", "t.z@example.com");
        var known = directory.search(agency, "Zielinski").getFirst().contactId();

        ring("contactId", known.toString(), "willingToPay", "3100.00");

        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_person where workspace_id = ? and email = ?",
            Integer.class, agency, "t.z@example.com")).isEqualTo(1);
        assertThat(interested.activeForUnit(agency, unitId))
            .filteredOn(party -> party.contactId().equals(known))
            .hasSize(2);
    }

    @Test
    void withdrawingTakesThemOffTheList() throws Exception {
        ring("givenName", "Krzysztof", "surname", "Wycofany", "email", "k.w@example.com");
        var interestId = interested.activeForUnit(agency, unitId).stream()
            .filter(party -> "Wycofany".equals(party.surname()))
            .findFirst().orElseThrow().interestId();

        mvc.perform(post("/units/" + unitId + "/interests/" + interestId + "/withdraw").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/units/" + unitId));

        mvc.perform(get("/units/" + unitId))
            .andExpect(content().string(not(containsString("Krzysztof Wycofany"))));
    }

    /**
     * A unit belonging to another agency must 404 rather than render an empty screen. An empty
     * screen says "nobody is interested", which is a different claim from "not yours" — and the
     * interest list alone would render empty quite happily.
     */
    @Test
    void aunitInAnotherAgencyIsNotFound() throws Exception {
        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        UUID theirs = workspaces.create("Cudza agencja", stranger, LocalDate.now());
        var theirProperty = portfolio.createProperty(theirs, "ul. Cudza 9, Sopot",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        var theirUnit = portfolio.addUnit(theirs, theirProperty, "m. 2", new BigDecimal("2000"));
        projections.runOnce();

        mvc.perform(get("/units/" + theirUnit)).andExpect(status().isNotFound());
    }

    /**
     * A unit id nobody ever created must 404 the same way a foreign one does. If unknown and
     * foreign answered differently, a caller could distinguish "not yours" from "does not exist" —
     * exactly the disclosure the undifferentiated 404 exists to prevent.
     */
    @Test
    void aunitThatWasNeverCreatedIsNotFound() throws Exception {
        mvc.perform(get("/units/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    /** Without this, the refusal above could be CSRF, or a route that does not exist. */
    @Test
    void thewriteIsRefusedWithoutACsrfToken() throws Exception {
        mvc.perform(post("/units/" + unitId + "/interests")
                .param("givenName", "Nikt").param("surname", "Nigdy"))
            .andExpect(status().isForbidden());
    }

    /**
     * A stale or foreign interest id must 404, not render a 500 page — the case
     * {@code InterestService.withdraw} already refuses via {@code NoSuchInterestException}, which
     * previously had no mapping for this screen.
     *
     * <p>Not literally "withdraw the same interest twice": a second withdraw of an already-withdrawn
     * interest this workspace still owns now throws {@code InterestNotActiveException}, not
     * {@code NoSuchInterestException} — see {@code InterestRepository.find} and
     * {@code InterestService.requireActive}, which is a distinct error-mapping concern outside this
     * fix wave. An id nobody ever registered exercises the {@code NoSuchInterestException} path a
     * double-submit would hit if the row had truly gone away.
     */
    @Test
    void withdrawingAnInterestThatWasNeverRegisteredIsNotFound() throws Exception {
        mvc.perform(post("/units/" + unitId + "/interests/" + UUID.randomUUID() + "/withdraw")
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    /**
     * A {@code contactId} that is a valid UUID but belongs to another workspace must 404, not render
     * a 500 page. Registered directly through {@link ContactService} rather than through the screen,
     * because the point of this test is the foreign id reaching {@code InterestService.register} —
     * how the contact came to exist in the other workspace is incidental.
     */
    @Test
    void postingAnInterestWithAContactIdFromAnotherWorkspaceIsNotFound() throws Exception {
        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        UUID theirs = workspaces.create("Cudza agencja (kontakt)", stranger, LocalDate.now());
        UUID theirContact = contactService.registerLead(theirs,
            new ContactDetails("Obcy", "Kontakt", "obcy.kontakt@example.com", null),
            true, LocalDate.now());

        mvc.perform(post("/units/" + unitId + "/interests").with(csrf())
                .param("contactId", theirContact.toString())
                .param("willingToPay", "1000.00"))
            .andExpect(status().isNotFound());
    }

    /** The malformed-id claim {@code LeadFormTest} makes about {@code chosen} is true end to end. */
    @Test
    void amalformedContactIdOnTheGetIsABadRequest() throws Exception {
        mvc.perform(get("/units/" + unitId).param("contactId", "not-a-uuid"))
            .andExpect(status().isBadRequest());
    }

    /**
     * A well-formed {@code contactId} this workspace does not know must be refused the same way a
     * malformed one is — not silently downgraded to the create-a-new-person form. It is a not-found,
     * not bad input, so it is a 404 rather than the 400 the previous test gets.
     */
    @Test
    void awellFormedButForeignContactIdOnTheGetIsNotFound() throws Exception {
        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        UUID theirs = workspaces.create("Cudza agencja (widok)", stranger, LocalDate.now());
        UUID theirContact = contactService.registerLead(theirs,
            new ContactDetails("Obcy", "Widok", "obcy.widok@example.com", null),
            true, LocalDate.now());

        mvc.perform(get("/units/" + unitId).param("contactId", theirContact.toString()))
            .andExpect(status().isNotFound());
    }
}
