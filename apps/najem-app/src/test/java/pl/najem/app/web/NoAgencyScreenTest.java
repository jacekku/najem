package pl.najem.app.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import pl.najem.app.SharedDatabase;
import pl.najem.um.application.UserService;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A person who belongs to no agency gets a screen that explains it, not a 403.
 *
 * <p>The human's ruling (najem-build seq 309): <i>"a person who has just been invited is not an
 * error"</i>. Fail-closed is about permissions rather than copy — this response carries no agency
 * and therefore no agency's data, which is the property that matters.
 *
 * <p>Its own operator subject, because "belongs to nothing" must not be satisfied by accident. It
 * shares {@link pl.najem.app.SharedDatabase}'s container with every other application test, which
 * is safe for a reason worth stating: {@code WebWorkspaceResolver} asks
 * {@code access.forSubject(subject)}, so "belongs to no agency" is a fact about THIS SUBJECT and
 * not about the database. A neighbouring test's agency belongs to a different operator and is
 * invisible here. The earlier version of this comment claimed the whole database had to be empty;
 * it did not, and believing it would have cost a container per class forever.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + NoAgencyScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Tag("integration")
class NoAgencyScreenTest extends SharedDatabase {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000003";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService users;

    @Test
    void aUserWithNoMembershipIsToldSoRatherThanRefused() throws Exception {
        users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));

        var response = mvc.perform(get("/")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
            .contains("Nie należysz jeszcze do żadnej agencji")
            // The denial copy must NOT be what a new user meets.
            .doesNotContain("Brak dostępu")
            // This screen is one of three WebErrorAdvice renders through an @ExceptionHandler
            // rather than an ordinary controller method — Spring does not run a
            // @ControllerAdvice's @ModelAttribute methods (ActiveAgencyAdvice's currentPath among
            // them) for that invocation, so WebErrorAdvice sets currentPath itself. Asserted here
            // as the sidebar's own active-item class actually landing on "/" — not merely that the
            // page renders without throwing, which a null-safe expression alone would already
            // guarantee while leaving the highlight silently dead.
            .contains("nav__item--active");
    }
}
