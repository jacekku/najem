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
 * The workspace seam. A screen RECEIVES a workspace; it never resolves one, and it never reads the
 * {@code X-Workspace-Id} dev header — see {@link NoDevHeaderTest}.
 *
 * <p>Runs with a configured platform operator and no issuer, which is the local-development shape:
 * {@code WorkspaceCaller} resolves the operator rather than a token. The seam is identical either
 * way — that is the point of resolving through UserManagement rather than reinventing it here.
 */
@SpringBootTest(properties = "najem.bootstrap.operator-subject=" + WebWorkspaceTest.OPERATOR)
@AutoConfigureMockMvc
@Testcontainers
class WebWorkspaceTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000001";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService users;
    @Autowired
    WorkspaceService workspaces;

    UUID operatorUserId;

    @BeforeEach
    void registerOperator() {
        operatorUserId = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
    }

    @Test
    void aSubjectWithOneMembershipGetsThatWorkspace() throws Exception {
        UUID workspaceId = workspaces.create("Agencja Pierwsza", operatorUserId, LocalDate.now());

        String html = mvc.perform(get("/workspace"))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(workspaceId.toString()).contains("ADMIN");
    }

    /**
     * A session value is client-influenced input, not a credential, so membership is checked on
     * EVERY request rather than only when the workspace was chosen. Otherwise a subject removed
     * from a workspace keeps reaching it until they happen to log out.
     */
    @Test
    void aSessionHeldWorkspaceIsRevalidatedOnEveryRequest() throws Exception {
        workspaces.create("Agencja Druga", operatorUserId, LocalDate.now());
        UUID somebodyElses = UUID.randomUUID();

        var session = new MockHttpSession();
        session.setAttribute("najem.activeWorkspace", somebodyElses);

        int status = mvc.perform(get("/workspace").session(session))
            .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }
}
