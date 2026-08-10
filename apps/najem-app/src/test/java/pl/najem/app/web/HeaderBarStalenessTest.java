package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.najem.app.SharedDatabase;
import pl.najem.reporting.application.ProjectionStatus;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The staleness badge, exercised with a deterministic answer from {@link ProjectionStatus}.
 *
 * <p>Kept out of {@link WebScaffoldTest} deliberately, even though the two share almost everything
 * about their setup. {@code ProjectionStatus.all()} reads the real {@code reporting_checkpoint}
 * table, which {@code ProjectionRunner} advances on a one-second scheduler tick running in the same
 * shared context every other test in this package uses — so whether a request in
 * {@code WebScaffoldTest} lands "behind" or "caught up" depends on exactly how much wall-clock time
 * has passed since the workspace-creation event was written, which is exactly the kind of assertion
 * that passes locally and flakes in CI (or the reverse). A {@link MockBean} answer removes that
 * race, at the cost of a second Testcontainers Postgres container for this one class (najem-build
 * seq 328 accepted the same trade for {@code SecuredBranchTest}, for the same reason: a genuinely
 * different context is worth a second container, a convenience one is not).
 *
 * <p>One seeded workspace, not one per test: under permit-all the acting user is always the
 * configured platform operator (regardless of which account a test registers — see
 * {@code ActingCaller.unauthenticatedOperator}), so a second workspace on that same operator would
 * give it two memberships and send every request in this class to the chooser instead of home
 * (the pitfall {@code WebWorkspaceTest} already warns about). The two tests below vary only the
 * mocked answer, never the seeded state.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + HeaderBarStalenessTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Tag("integration")
class HeaderBarStalenessTest extends SharedDatabase {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000005";

    @Autowired
    MockMvc mvc;
    @Autowired
    UserService users;
    @Autowired
    WorkspaceService workspaces;
    @MockBean
    ProjectionStatus projections;

    static boolean seeded;

    @BeforeEach
    void anAgency() {
        if (seeded) {
            return;
        }
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        workspaces.create("Agencja Zaległa", operator, LocalDate.now());
        seeded = true;
    }

    /**
     * A manager who has just created something must be able to tell "not yet" from "it didn't
     * work". The mockups have no home for this badge; dropping it would have been the quiet
     * option, and it is the condition on which an eventually consistent board was accepted.
     */
    @Test
    void theHeaderBarStillSaysWhenTheViewIsBehind() throws Exception {
        when(projections.all()).thenReturn(List.of(new ProjectionStatus.Status("unit_board", 3)));

        String html = mvc.perform(get("/"))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("headerbar__stale").contains("aktualizacja…");
    }

    /** The other side of the same flag: caught up says nothing rather than something stale. */
    @Test
    void theHeaderBarStaysQuietWhenNothingIsBehind() throws Exception {
        when(projections.all()).thenReturn(List.of(new ProjectionStatus.Status("unit_board", 0)));

        String html = mvc.perform(get("/"))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("headerbar__stale");
    }
}
