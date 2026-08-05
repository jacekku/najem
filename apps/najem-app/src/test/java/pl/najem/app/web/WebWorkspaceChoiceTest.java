package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What happens when the workspace is ambiguous or disputed. Every case here resolves to DENIED,
 * per roadmap rule 7: uncertainty is never a permission.
 *
 * <p>Its own operator subject and its own container, so the two workspaces it creates cannot leak
 * into {@link WebWorkspaceTest}'s single-membership case.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + WebWorkspaceChoiceTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class WebWorkspaceChoiceTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000002";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService users;
    @Autowired
    WorkspaceService workspaces;

    /**
     * Static, because JUnit builds a new test instance per method: as instance fields the
     * {@code if (first == null)} guard was true every time and each test created two MORE
     * agencies for the same operator. Nothing failed, which is the problem — the fixture grew
     * silently and every later test ran against a different database than the one it read like.
     */
    static UUID first;
    static UUID second;

    @BeforeEach
    void twoWorkspaces() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (first == null) {
            first = workspaces.create("Agencja A", operator, LocalDate.now());
            second = workspaces.create("Agencja B", operator, LocalDate.now());
        }
    }

    /**
     * The case that made me change the resolver. Picking one — even deterministically, by earliest
     * joined — is a default deciding WHOSE data a request acts on, which rule 7 forbids. A
     * misdirected write is worse than a misdirected read because per-workspace uniqueness means it
     * never collides with the correct one, so removing the default later does not undo it.
     */
    @Test
    void severalMembershipsAndNoChoiceIsRefusedRatherThanGuessed() throws Exception {
        var response = mvc.perform(get("/workspace")).andReturn().getResponse();

        // Still no workspace resolved and still no data served — but the person is ASKED rather
        // than refused. Somebody with legitimate access to two agencies being told "Brak dostępu"
        // would be false, and the guess this avoids is the same guess either way.
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/agencies");
    }

    @Test
    void theChooserListsBothAgenciesByName() throws Exception {
        String html = mvc.perform(get("/agencies"))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Agencja A").contains("Agencja B");
    }

    /**
     * Carries a CSRF token deliberately. Without one this still answers 403 — but for want of a
     * token rather than for want of membership, and the test would stay green with the membership
     * check deleted. A refusal test has to pin down WHICH refusal it got.
     */
    @Test
    void choosingAnAgencyTheSubjectDoesNotBelongToIsRefused() throws Exception {
        int status = mvc.perform(post("/agencies/" + UUID.randomUUID()).with(csrf()))
            .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    /**
     * The choice sticks, and what it selects is the agency asked for rather than whichever one the
     * resolver would have reached for. Deliberately chooses {@code first} — the one an
     * earliest-joined default would also have returned is {@code first}, so this asserts on
     * {@code second} elsewhere ({@link #aChosenWorkspaceTheSubjectBelongsToIsHonoured}) and the
     * pair together distinguish "honoured the choice" from "happened to agree with it".
     */
    @Test
    void choosingAnAgencyThenActsInIt() throws Exception {
        var session = new MockHttpSession();

        mvc.perform(post("/agencies/" + first).session(session).with(csrf()))
            .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/"));

        String html = mvc.perform(get("/workspace").session(session))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Agencja A").doesNotContain("Agencja B");
    }

    /**
     * A session value is client-influenced input, not a credential, so membership is re-checked on
     * EVERY request rather than only when the workspace was chosen. Otherwise a subject removed
     * from a workspace keeps reaching it until they happen to log out.
     */
    @Test
    void aWorkspaceTheSubjectIsNotAMemberOfIsRefusedEvenWhenHeldInSession() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("najem.activeWorkspace", UUID.randomUUID());

        int status = mvc.perform(get("/workspace").session(session))
            .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    @Test
    void aChosenWorkspaceTheSubjectBelongsToIsHonoured() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("najem.activeWorkspace", second);

        String html = mvc.perform(get("/workspace").session(session))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Agencja B").doesNotContain("Agencja A");
    }
}
