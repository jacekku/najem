package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
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

/**
 * What happens when the workspace is ambiguous or disputed. Every case here resolves to DENIED,
 * per roadmap rule 7: uncertainty is never a permission.
 *
 * <p>Its own operator subject and its own container, so the two workspaces it creates cannot leak
 * into {@link WebWorkspaceTest}'s single-membership case.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + WebWorkspaceChoiceTest.OPERATOR,
    "najem.security.permit-all=true"
})
@AutoConfigureMockMvc
@Testcontainers
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

    UUID first;
    UUID second;

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
        int status = mvc.perform(get("/workspace"))
            .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
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

        assertThat(html).contains(second.toString()).doesNotContain(first.toString());
    }
}
