package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.Unit;
import pl.najem.reporting.application.ProjectionRunner;
import pl.najem.reporting.application.UnitBoardQuery;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adding a flat to a building through the screen a manager actually uses.
 *
 * <p>Container tier, modelled on {@link ReserveScreenTest}: real Postgres via
 * {@code @ServiceConnection}, {@code permit-all}, and projections drained explicitly.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + AddUnitScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class AddUnitScreenTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000030";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired PortfolioService portfolio;
    @Autowired ProjectionRunner projections;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventStore store;
    @Autowired UnitBoardQuery unitsQuery;

    /** Static: the agency is shared fixture, same reasoning as ReserveScreenTest. */
    static UUID agency;

    UUID workspaceId;
    UUID otherWorkspaceId;

    @BeforeEach
    void anAgencyAndAStranger() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency == null) {
            agency = workspaces.create("Agencja Dodawania Lokali", operator, LocalDate.now());
        }
        workspaceId = agency;

        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        otherWorkspaceId = workspaces.create("Cudza agencja (lokale)", stranger, LocalDate.now());
    }

    @Test
    void addingAUnitPutsItOnThePropertysBoard() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        mvc.perform(post("/properties/" + propertyId + "/units").with(csrf())
                .param("name", "M2").param("baseRent", "2500.00").param("action", "save"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/properties/" + propertyId + "/units"));

        projections.runOnce();
        mvc.perform(get("/properties/" + propertyId + "/units"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("M2")))
            // The format is stated, not inherited: Polish grouping and decimal separator.
            .andExpect(content().string(containsString("2 500,00")));
    }

    @Test
    void savingAndAddingAnotherComesBackToABlankFormNamingWhatWasJustSaved() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        var redirect = mvc.perform(post("/properties/" + propertyId + "/units").with(csrf())
                .param("name", "M3").param("baseRent", "3000").param("action", "another"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).startsWith("/properties/" + propertyId + "/units/new");

        mvc.perform(get(redirect))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Dodano lokal M3")))
            // Blank, not prefilled with what was just saved — the next flat has a different name.
            .andExpect(content().string(containsString("name=\"name\"")))
            .andExpect(content().string(not(containsString("value=\"M3\""))));
    }

    /**
     * The stream and the derived row must agree about the name.
     *
     * <p>{@code Unit.add} strips, and {@code addUnit} used to hand the projection the caller's raw
     * argument — so the event said "M2" and pm_unit said " M2 ". pm_unit is documented as derived
     * from the stream and {@code PostgresAttentionListsProjection} joins it, so this left two
     * derived tables permanently disagreeing about one unit, with nothing to notice. Asserted
     * against the event payload rather than against the literal, so it stays true if the
     * normalisation ever changes.
     */
    @Test
    void thepmUnitRowCarriesTheNameTheEventCarries() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        mvc.perform(post("/properties/" + propertyId + "/units").with(csrf())
                .param("name", "  M2 spacjowane  ").param("baseRent", "2500").param("action", "save"))
            .andExpect(status().is3xxRedirection());

        String unitId = jdbc.queryForObject(
            "select unit_id::text from pm_unit where property_id = ?::uuid", String.class,
            propertyId.toString());
        String inTheRow = jdbc.queryForObject("select name from pm_unit where unit_id = ?::uuid",
            String.class, unitId);
        String inTheStream = Unit.from(store.load(UUID.fromString(unitId), "Unit").events()).name();

        assertThat(inTheRow).isEqualTo(inTheStream);
        assertThat(inTheStream).isEqualTo("M2 spacjowane");
    }

    @Test
    void aunitWithNoNameIsRefused() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        mvc.perform(post("/properties/" + propertyId + "/units").with(csrf())
                .param("name", "   ").param("baseRent", "2500"))
            .andExpect(status().isBadRequest());
    }

    /**
     * Absence and foreign ownership give the same answer, because saying which would confirm that
     * another agency's id is real. Both the form and the submit — a form that renders and then
     * fails on submit is worse than one that never opens.
     */
    @Test
    void anotherAgencysPropertyIsNotFoundOnTheFormOrTheSubmit() throws Exception {
        UUID theirs = portfolio.createProperty(otherWorkspaceId, "Nie nasza 1", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        mvc.perform(get("/properties/" + theirs + "/units/new"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/properties/" + theirs + "/units").with(csrf())
                .param("name", "M2").param("baseRent", "2500"))
            .andExpect(status().isNotFound());
    }

    /**
     * The flash attribute {@code added} was already set by {@code create} on the save branch, but
     * {@code units.html} never read it — the confirmation vanished silently on arrival. Fails
     * without the template change: the flash carries "M4", the redirect lands on the board, and
     * nothing in the old markup contained "Dodano lokal". A shared {@link MockHttpSession} threads
     * the two calls together — Spring's flash map is session-backed, and MockMvc does not persist a
     * session across independent {@code perform} calls on its own (same reason
     * WebWorkspaceChoiceTest passes one explicitly).
     */
    @Test
    void savingAUnitLandsOnTheBoardShowingTheConfirmation() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        var session = new MockHttpSession();

        var redirect = mvc.perform(post("/properties/" + propertyId + "/units").with(csrf())
                .session(session)
                .param("name", "M4").param("baseRent", "2000").param("action", "save"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        mvc.perform(get(redirect).session(session))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Dodano lokal M4")));
    }

    /**
     * {@code baseRent} renders {@code type="text"} with {@code inputmode="decimal"} — this
     * codebase's own established convention for a money field (the same shape
     * {@code willingToPay} and {@code reserve-terms.html}'s money fields already use), not
     * {@code type="number"} with {@code step}/{@code min}. Pinned so the field cannot silently
     * revert: the server parses {@code baseRent} as {@link BigDecimal} regardless of the client-side
     * input type, so nothing else here would notice a revert.
     */
    @Test
    void thebaseRentFieldIsATextInputWithDecimalInputmode() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        String html = mvc.perform(get("/properties/" + propertyId + "/units/new"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        java.util.regex.Matcher tag = java.util.regex.Pattern
            .compile("<input[^>]*\\bname=\"baseRent\"[^>]*>")
            .matcher(html);
        assertThat(tag.find()).as("an <input name=\"baseRent\"> element").isTrue();
        assertThat(tag.group())
            .as("baseRent must be a text input with a decimal inputmode, not a number spinner")
            .contains("type=\"text\"")
            .contains("inputmode=\"decimal\"")
            .doesNotContain("type=\"number\"");
    }

    @Test
    void theunitsBoardOffersTheAddButton() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        projections.runOnce();
        mvc.perform(get("/properties/" + propertyId + "/units"))
            .andExpect(content().string(containsString("Dodaj lokal")))
            .andExpect(content().string(containsString("/properties/" + propertyId + "/units/new")));
    }
}
