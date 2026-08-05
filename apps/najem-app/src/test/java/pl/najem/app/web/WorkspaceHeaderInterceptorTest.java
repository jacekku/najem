package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Every module's REST controller takes the workspace from a client-supplied {@code X-Workspace-Id}
 * header and checks it against nothing. No module can check it for itself: none depends on
 * usermanagement, so {@code WorkspaceAccess} is unreachable from accounting, contacts, reporting
 * and propertymanagement <em>by construction</em> (najem-reviewer, najem-build seq 194 §3 / 272).
 *
 * <p>The composition root is the only place the check can live, which is where the seam already is.
 * This applies it to {@code /api/**} rather than only to controllers that declare a
 * {@link WebWorkspace} parameter.
 *
 * <p><b>A named workspace the caller is not a member of answers 404, not 403.</b> 403 confirms the
 * workspace exists, which turns the header into an existence oracle for other agencies' ids.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + WorkspaceHeaderInterceptorTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class WorkspaceHeaderInterceptorTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000003";

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
     * Static on purpose. As an instance field this is reset per test, so the null-guard creates a
     * fresh workspace for every method — the operator ends up in four, the resolver correctly
     * refuses to guess between them, and the screen test fails for a reason that has nothing to do
     * with the interceptor. Second time today a shared stateful fixture has done this to me.
     */
    static UUID mine;

    @BeforeEach
    void aWorkspaceIBelongTo() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (mine == null) {
            mine = workspaces.create("Moja Agencja", operator, LocalDate.now());
        }
    }

    @Test
    void servesAWorkspaceTheCallerBelongsTo() throws Exception {
        int status = mvc.perform(get("/api/acc/board").header("X-Workspace-Id", mine))
            .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }

    /**
     * The finding this exists for: an authenticated caller naming any other agency's workspace was
     * served it, because the header was trusted and never compared with anything.
     */
    @Test
    void refusesAWorkspaceTheCallerIsNotAMemberOf() throws Exception {
        UUID somebodyElses = UUID.randomUUID();

        int status = mvc.perform(get("/api/acc/board").header("X-Workspace-Id", somebodyElses))
            .andReturn().getResponse().getStatus();

        assertThat(status)
            .as("404 rather than 403: a 403 would confirm the workspace exists, which makes the "
                + "header an existence oracle for other agencies' ids")
            .isEqualTo(404);
    }

    /** Applies to writes as well as reads — the header is trusted identically on both. */
    @Test
    void refusesAWriteAimedAtAWorkspaceTheCallerIsNotAMemberOf() throws Exception {
        int status = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/acc/ingest/fetch")
                    .header("X-Workspace-Id", UUID.randomUUID()))
            .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }

    /**
     * The fail-open this interceptor was held back for. {@code CurrentUser.subject} throws
     * AccessDeniedException for a token whose {@code sub} is not a UUID — the same exception type
     * thrown when there is no caller at all. Catching around both let a PRESENT token that had
     * passed signature, expiry, issuer and audience fall into the "nobody is authenticated, this
     * must be permit-all" branch, and the header was waved through unchecked.
     *
     * <p>This asks for a workspace the caller is NOT a member of, so a pass here would be the
     * interceptor skipping its check rather than performing it.
     */
    @Test
    void aTokenWhoseSubjectIsNotAUserIdIsDeniedRatherThanTreatedAsUnauthenticated() throws Exception {
        int status = mvc.perform(get("/api/acc/board")
                .header("X-Workspace-Id", UUID.randomUUID())
                .with(jwt().jwt(token -> token.subject("not-a-uuid"))))
            .andReturn().getResponse().getStatus();

        assertThat(status)
            .as("a present-but-unusable token is a denial, not an absence of authentication")
            .isNotEqualTo(200);
    }

    /**
     * The other half, so the test above cannot pass merely because tokens break everything. A
     * usable token naming a workspace this subject belongs to is served.
     */
    @Test
    void aTokenWhoseSubjectIsAMemberIsServed() throws Exception {
        int status = mvc.perform(get("/api/acc/board")
                .header("X-Workspace-Id", mine)
                .with(jwt().jwt(token -> token.subject(OPERATOR))))
            .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }

    /**
     * Screens are already covered by {@link WebWorkspaceResolver} and never send the header, so the
     * interceptor must not reach them — otherwise it would demand of a screen the very thing the
     * seam exists to stop it sending.
     */
    @Test
    void leavesTheScreensToTheSeamThatAlreadyCoversThem() throws Exception {
        int status = mvc.perform(get("/workspace")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }
}
