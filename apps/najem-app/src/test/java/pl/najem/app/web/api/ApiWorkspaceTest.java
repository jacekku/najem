package pl.najem.app.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A module's API takes its workspace from the caller, and {@code X-Workspace-Id} does nothing.
 *
 * <p>The header is sent here <b>deliberately and with a foreign value</b>. Asserting only that a
 * request without it still works would leave the interesting case untested: what used to happen is
 * that a header was believed, and later that it was believed-after-a-check. Both of those pass a
 * test that never sends one. So the assertion is that a caller who tries to act in somebody else's
 * agency is not refused — they are simply not listened to, and their write lands in their own books.
 *
 * <p>Not refused, on purpose. A 404 would mean the application still reads the header far enough to
 * evaluate it. Ignoring an input completely is a stronger property than rejecting it, and the only
 * one that stays true as endpoints are added by people who never heard of the header.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + ApiWorkspaceTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class ApiWorkspaceTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-0000000000a1";

    /** Belongs to nobody. If the header were read, the property would be created in this. */
    private static final UUID SOMEBODY_ELSES = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService users;
    @Autowired
    WorkspaceService workspaces;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @Test
    void aWriteLandsInTheCallersWorkspaceEvenWhenTheHeaderNamesAnother() throws Exception {
        UUID mine = onlyMembership();

        String body = mvc.perform(post("/api/pm/properties")
                .header("X-Workspace-Id", SOMEBODY_ELSES.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"ul. Próbna 1, 00-001 Warszawa\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        UUID propertyId = UUID.fromString(json.readTree(body).get("propertyId").asText());

        UUID landedIn = jdbc.queryForObject(
            "select workspace_id from pm_property where property_id = ?", UUID.class, propertyId);

        assertThat(landedIn)
            .as("the workspace must come from the caller's membership, never from the header")
            .isEqualTo(mine)
            .isNotEqualTo(SOMEBODY_ELSES);
    }

    /**
     * The bean wiring is the guard, so it is asserted rather than assumed. With no
     * {@code najem.test.workspace-header} set — as here, and as in any packaged deployment — the
     * resolver in the context must be the one that reads memberships. If the conditions were ever
     * to both match, or both not match, this is what says so.
     */
    @Test
    void theResolverInAPlainDeploymentIsTheOneThatReadsMemberships(
        @Autowired ApiWorkspaceResolver resolver) {

        assertThat(resolver).isInstanceOf(ApiWorkspaceArgumentResolver.class);
    }

    private UUID onlyMembership() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        return workspaces.create("Agencja Jedyna", operator, LocalDate.now());
    }
}
