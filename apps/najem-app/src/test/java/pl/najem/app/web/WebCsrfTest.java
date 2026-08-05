package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Whether the person INTENDED the request, which is the one question no other control here asks.
 *
 * <p>The agency a request acts in lives in the {@code HttpSession}, and a session is cookie-backed,
 * so a browser attaches it to a cross-site request. Everything else in the stack still passes while
 * that happens — the token is valid, the audience matches, membership is re-checked — because the
 * victim really is a member of the agency being switched to. What a forged request achieves is not
 * reading or writing directly: it silently changes WHICH agency the victim's next action lands in.
 *
 * <p>The API surface is the opposite case and is exempt: it authenticates with a bearer token,
 * which a browser does not attach cross-site, so protection there costs machine clients a 403 and
 * buys nothing. Both halves are asserted — a rule that only ever refuses is indistinguishable from
 * one that refuses everything.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + WebCsrfTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Testcontainers
class WebCsrfTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000009";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService users;
    @Autowired
    WorkspaceService workspaces;

    /** Static: JUnit builds a new instance per method, so an instance guard would never be false. */
    static UUID agency;

    @BeforeEach
    void anAgencyThisOperatorBelongsTo() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency == null) {
            agency = workspaces.create("Agencja CSRF", operator, LocalDate.now());
        }
    }

    @Test
    void switchingAgencyWithoutATokenIsRefused() throws Exception {
        int status = mvc.perform(post("/agencies/" + agency).session(new MockHttpSession()))
            .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    /**
     * The assertion that stops the one above passing for the wrong reason. Without it, the refusal
     * could be membership, a bad id, or a route that does not exist, and the test would stay green
     * if CSRF were switched off again tomorrow.
     */
    @Test
    void theSameSwitchWithATokenIsHonoured() throws Exception {
        var response = mvc.perform(post("/agencies/" + agency)
                .session(new MockHttpSession())
                .with(csrf()))
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    /**
     * A bearer-authenticated API is not reachable by forgery, so protecting it would refuse every
     * machine client for nothing. Asserted as "not a CSRF refusal" rather than as a specific
     * success, because what this endpoint answers is the property owner's business and may change.
     */
    @Test
    void theApiIsNotRefusedForWantOfAToken() throws Exception {
        int status = mvc.perform(post("/api/pm/properties")
                .header("X-Workspace-Id", agency)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"ul. Testowa 1, 00-001 Warszawa\"}"))
            .andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(403);
    }
}
