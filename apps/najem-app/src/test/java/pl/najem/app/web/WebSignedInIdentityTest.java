package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Who a request acts as, once people can sign in.
 *
 * <p>Controllers ask for a {@code Jwt}, because that is what a bearer-authenticated API call
 * carries. Somebody who signed in through the browser has an {@code OidcUser} instead, so they
 * reach those controllers with a {@code null} jwt — and the rule for "no token" is <em>fall back to
 * the configured platform operator</em>. <b>Every signed-in person would have acted as one shared
 * account.</b> Not a refusal, which is why nothing would have failed: the operator has an agency,
 * so the screens would have rendered, and a manager would have been shown somebody else's book.
 *
 * <p>Both tests below are about the same line, from opposite sides: a person who has an account
 * must be served their own agency, and a person who has none must not inherit the operator's.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + WebSignedInIdentityTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Testcontainers
class WebSignedInIdentityTest {

    /** The account every unauthenticated request falls back to, and the one nobody may become. */
    static final String OPERATOR = "3f1d9c22-0000-4000-8000-00000000000a";

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
    static UUID managersSubject;

    @BeforeEach
    void anOperatorWithAnAgency_andAManagerWithTheirOwn() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (managersSubject == null) {
            workspaces.create("Agencja Operatora", operator, LocalDate.now());

            managersSubject = UUID.randomUUID();
            UUID manager = users.register(managersSubject, LocalDate.now());
            workspaces.create("Agencja Managera", manager, LocalDate.now());
        }
    }

    @Test
    void aSignedInPersonIsServedTheirOwnAgencyAndNotTheOperators() throws Exception {
        String html = mvc.perform(get("/workspace").with(oidcLogin()
                .idToken(token -> token.subject(managersSubject.toString()))))
            .andReturn().getResponse().getContentAsString();

        assertThat(html)
            .contains("Agencja Managera")
            .as("falling through to the operator is silent: their agency renders perfectly")
            .doesNotContain("Agencja Operatora");
    }

    /**
     * The other side. NAJEM is invite-only, so a valid Keycloak account is not a NAJEM account —
     * and somebody who has signed in but never been invited must be refused rather than quietly
     * handed the operator's agency.
     */
    @Test
    void signingInWithoutAnInvitationDoesNotInheritTheOperatorsAgency() throws Exception {
        var response = mvc.perform(get("/workspace").with(oidcLogin()
                .idToken(token -> token.subject(UUID.randomUUID().toString()))))
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
            .as("refused in words that name the actual problem — an invitation, not a permission")
            .contains("nie ma jeszcze dostępu")
            .doesNotContain("Agencja Operatora");
    }
}
