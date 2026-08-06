package pl.najem.app.web.api;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two agencies and no way to say which: the API refuses rather than picking one.
 *
 * <p>This is the case the header used to answer, and answering it is what made the header worth
 * having. Removing it has a real cost — a bearer-token client belonging to several agencies can no
 * longer reach any of them — and that cost is the correct one to pay. Choosing the first membership
 * would be a default deciding whose books a write lands in, which rule 7 forbids by name, and a
 * write into the wrong agency does not announce itself: uniqueness is per workspace, so nothing
 * collides and nothing complains.
 *
 * <p>Its own database and its own subject, deliberately. Sharing a container with the
 * single-membership case makes "the only membership" depend on JUnit's method order — the failure
 * {@code WebWorkspaceTest} records having already been through once.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + ApiWorkspaceChoiceTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class ApiWorkspaceChoiceTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-0000000000b2";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService users;
    @Autowired
    WorkspaceService workspaces;

    @Test
    void severalAgenciesAndNoneChosenIsAConflictNotAGuess() throws Exception {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        workspaces.create("Agencja Pierwsza", operator, LocalDate.now());
        workspaces.create("Agencja Druga", operator, LocalDate.now());

        mvc.perform(get("/api/acc/board"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("choose-agency"));
    }

    /**
     * And naming one in the header does not resolve it. The temptation this guards against is
     * obvious and would look like a kindness: the caller told us which agency they meant, they are
     * a member of it, so why refuse? Because "they told us" is the whole mechanism being removed —
     * reinstating it for the awkward case reinstates it, and the awkward case is the one an
     * attacker picks.
     */
    @Test
    void namingOneInTheHeaderStillDoesNotResolveIt() throws Exception {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        UUID first = workspaces.create("Agencja Trzecia", operator, LocalDate.now());
        workspaces.create("Agencja Czwarta", operator, LocalDate.now());

        mvc.perform(get("/api/acc/board").header("X-Workspace-Id", first.toString()))
            .andExpect(status().isConflict());
    }
}
