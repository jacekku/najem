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
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The workspace seam with a single membership — the unambiguous case. A screen RECEIVES a
 * workspace; it never resolves one, and it never reads the {@code X-Workspace-Id} dev header
 * (see {@link NoDevHeaderTest}).
 *
 * <p>Deliberately holds exactly one test that creates exactly one workspace. An earlier version
 * shared an operator and a container with the several-membership cases, and passed or failed on
 * JUnit's method order: a workspace created by a neighbouring test changed what "the only
 * membership" meant. @najem-accounting hit the same shape at najem-build seq 129 — a fixture
 * shared with a stateful path couples every test to every other one. The ambiguous cases live in
 * {@link WebWorkspaceChoiceTest}, with their own subject and their own database.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + WebWorkspaceTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
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

    @Test
    void aSubjectWithOneMembershipGetsThatWorkspace() throws Exception {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        UUID workspaceId = workspaces.create("Agencja Pierwsza", operator, LocalDate.now());

        String html = mvc.perform(get("/workspace"))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(workspaceId.toString()).contains("ADMIN");
    }
}
