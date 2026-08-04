package pl.najem.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scaffold every later screen renders into. Boots the whole application context on purpose:
 * a module whose beans stop being constructible fails here as well as in e2e, which is the second
 * reason the UI lives in the composition root (najem-build seq 130).
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + WebScaffoldTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Testcontainers
class WebScaffoldTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000004";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    pl.najem.um.application.UserService users;
    @Autowired
    pl.najem.um.application.WorkspaceService workspaces;

    /** "/" is the agency screen now, so it needs an agency to be the screen of. */
    static boolean seeded;

    @org.junit.jupiter.api.BeforeEach
    void anAgency() {
        if (seeded) {
            return;
        }
        java.util.UUID operator = users.findBySubject(java.util.UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(java.util.UUID.fromString(OPERATOR),
                java.time.LocalDate.now()));
        workspaces.create("Agencja Testowa", operator, java.time.LocalDate.now());
        seeded = true;
    }

    @Test
    void servesAnHtmlPageAtTheRoot() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void servesHtmxFromTheApplicationRatherThanACdn() throws Exception {
        mvc.perform(get("/vendor/htmx.min.js"))
            .andExpect(status().isOk());
    }

    /**
     * The units screen and the timeline screen it links to, wired end to end: controller, the
     * reporting query they call, and the template. Asked about a property with nothing in it, so
     * no PM events need seeding — what this proves is that the three parts connect and that an
     * empty answer renders as an empty screen rather than an error.
     *
     * <p>Shares this class's context deliberately. Every one of these boots the whole application,
     * and on this machine a second context costs a second Postgres container (najem-build seq 328).
     */
    @Test
    void theUnitsAndTimelineScreensRenderForAPropertyWithNothingInIt() throws Exception {
        java.util.UUID nothing = java.util.UUID.randomUUID();

        mvc.perform(get("/properties/" + nothing + "/units"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("nie ma jeszcze")));

        mvc.perform(get("/tenancies/" + nothing + "/timeline"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Brak zapisów")));
    }

    /**
     * Every screen says which agency it is showing, not only the home page. Once a person can
     * belong to several and switch, a screen that stays silent about it invites acting in the
     * wrong one — and a misdirected write never collides with the correct one, so nothing tells
     * them. Asserted on a screen that is not the home page, because that is the case that was
     * missing.
     */
    @Test
    void everyScreenNamesTheAgencyItIsShowing() throws Exception {
        mvc.perform(get("/properties"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Agencja Testowa")));
    }

    /**
     * A page that pulls its script from a third party is a page whose render depends on that party
     * being reachable, in an application handling tenancy-scoped financial data (plan decision E).
     */
    @Test
    void theLayoutReferencesNoExternalHost() throws Exception {
        String html = mvc.perform(get("/"))
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
            .doesNotContain("http://")
            .doesNotContain("https://");
    }
}
