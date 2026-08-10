package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.application.ArrearsBoardProjection;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Raporty screen: its two tabs, and the arrears board's colour-to-pill mapping — the one
 * screen where a colour carries a statutory consequence (BRIGHT_RED: a whole billing period
 * unpaid, the art. 11 termination counter running).
 *
 * <p>{@link ArrearsBoardProjection} and {@link TenancyLabels} are mocked, the same shape
 * {@code HeaderBarStalenessTest} already uses for {@code ProjectionStatus}: what this test asks is
 * "given every {@link ArrearsColour} value, does the template render the right tone and the right
 * words", not "does accounting compute the right colour for a given ledger", which is
 * {@code ArrearsBoardTest}'s job in the accounting module, not this screen's.
 *
 * <p>This is what stops someone later "simplifying" the colour switch and quietly merging two
 * legally distinct states — RED and BRIGHT_RED look the same in a diff that only changes a string
 * literal, and used to look the same on screen too, until fix round 1 gave BRIGHT_RED its own
 * {@code pill--critical} tone.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + ReportsScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class ReportsScreenTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000060";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @MockBean ArrearsBoardProjection board;
    @MockBean TenancyLabels labels;

    static boolean seeded;

    @BeforeEach
    void anAgency() {
        if (seeded) {
            return;
        }
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        workspaces.create("Agencja Zaległości", operator, LocalDate.now());
        seeded = true;
        when(labels.forWorkspace(any(), any())).thenReturn(Map.of());
    }

    @Test
    void everyArrearsColourRendersItsOwnPillToneAndWords() throws Exception {
        when(board.forWorkspace(any())).thenReturn(List.of(
            new ArrearsBoardProjection.Row(UUID.randomUUID(), ArrearsColour.GOLDEN, 0),
            new ArrearsBoardProjection.Row(UUID.randomUUID(), ArrearsColour.GREEN, 0),
            new ArrearsBoardProjection.Row(UUID.randomUUID(), ArrearsColour.YELLOW, 0),
            new ArrearsBoardProjection.Row(UUID.randomUUID(), ArrearsColour.RED, 1),
            new ArrearsBoardProjection.Row(UUID.randomUUID(), ArrearsColour.BRIGHT_RED, 3)));

        String html = mvc.perform(get("/reports"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).as("GOLDEN: paid tone, its own words")
            .contains("pill--paid").contains("Zapłacone do końca najmu");
        assertThat(html).as("GREEN: paid tone (shared with GOLDEN, the user's own decision), its own words")
            .contains("Nic nie zalega");
        assertThat(html).as("YELLOW: warn tone, its own words")
            .contains("pill--warn").contains("Termin jeszcze nie minął");
        assertThat(html).as("RED: danger tone, its own words — distinct from BRIGHT_RED's critical tone")
            .contains("pill--danger").contains("Po terminie");
        assertThat(html).as("BRIGHT_RED: its own critical tone, its own words, and the art. 11 reference")
            .contains("pill--critical").contains("Cały okres bez zapłaty").contains("art. 11");
    }

    /**
     * The specific regression fix round 1 exists to prevent: BRIGHT_RED must not fall back to the
     * same tone RED uses. Counted rather than asserted as simple presence/absence, because the
     * legend always contributes one {@code pill--danger} (its own RED entry) and one
     * {@code pill--critical} (its own BRIGHT_RED entry) regardless of which colours appear as
     * rows — with a single BRIGHT_RED row and no RED row, a SECOND {@code pill--critical} is the
     * tell that the row itself rendered critical. If the row had fallen back to danger instead,
     * this would read 1 critical / 2 danger rather than 2 critical / 1 danger.
     */
    @Test
    void brightRedDoesNotShareRedsDangerTone() throws Exception {
        when(board.forWorkspace(any())).thenReturn(List.of(
            new ArrearsBoardProjection.Row(UUID.randomUUID(), ArrearsColour.BRIGHT_RED, 3)));

        String html = mvc.perform(get("/reports"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(occurrences(html, "pill--critical")).isEqualTo(2);
        assertThat(occurrences(html, "pill--danger")).isEqualTo(1);
    }

    private static int occurrences(String html, String needle) {
        return html.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void anEmptyBoardShowsNoLegendAndTheEmptyState() throws Exception {
        when(board.forWorkspace(any())).thenReturn(List.of());

        String html = mvc.perform(get("/reports"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Brak najmów do pokazania.");
        assertThat(html).doesNotContain("class=\"legend\"");
    }

    /**
     * The regression test for fix round 1's item 1: a {@code th:if} on the same element as its
     * {@code th:replace} never guards anything, because Thymeleaf resolves {@code th:replace} at
     * precedence 100 and {@code th:if} at 300 — the fragment substitutes the element before the
     * guard is ever evaluated. That bug shipped once already and a full green suite did not catch
     * it, because nothing asserted the empty state's ABSENCE when rows exist — only its presence
     * when they do not (the test above). Both directions have to be covered, or the guard can go
     * dead again and nothing here would notice.
     */
    @Test
    void aPopulatedBoardDoesNotShowTheEmptyState() throws Exception {
        when(board.forWorkspace(any())).thenReturn(List.of(
            new ArrearsBoardProjection.Row(UUID.randomUUID(), ArrearsColour.GREEN, 0)));

        String html = mvc.perform(get("/reports"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("Brak najmów do pokazania.");
    }

    /**
     * The screen opens on Zaległości, and both tabs are always offered.
     *
     * <p>The tab strip is the fragment's first real use outside the {@code /design} gallery, and
     * the one thing that can go wrong silently is the {@code active} value not matching any item's
     * label — the strip still renders, with nothing selected, which reads as a styling glitch
     * rather than as a broken model attribute. Asserting the active class is present is what
     * separates the two.
     */
    @Test
    void theScreenOpensOnArrearsAndOffersBothTabs() throws Exception {
        when(board.forWorkspace(any())).thenReturn(List.of());

        String html = mvc.perform(get("/reports"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Zaległości").contains("Rozliczenia właścicieli");
        assertThat(html).as("a tab strip with no active item means activeTab matched no label")
            .contains("tabs__item--active");
        assertThat(html).as("the arrears tab is the default, so its own empty state shows")
            .contains("Brak najmów do pokazania.");
    }

    /**
     * The owner-settlements tab is honest about not existing yet, and does not fall through to the
     * arrears board.
     *
     * <p>The absence assertion is the load-bearing half. A tab that renders the wrong panel is
     * indistinguishable from a correct one in a test that only checks for its own text, because
     * both panels contain Polish prose and both are on the same page in the source.
     */
    @Test
    void theOwnerSettlementsTabSaysItIsNotBuiltYet() throws Exception {
        String html = mvc.perform(get("/reports").param("tab", "wlasciciele"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Rozliczenia właścicieli nie są jeszcze dostępne.");
        assertThat(html).as("the arrears panel must not render under the other tab")
            .doesNotContain("class=\"legend\"")
            .doesNotContain("Brak najmów do pokazania.");
    }

    /**
     * The whole {@code ?tab=} contract in one test: absent opens Zaległości, {@code wlasciciele}
     * opens the owners tab, and anything else is a 404.
     *
     * <p>The 404 is what needed covering. It was added as a behaviour change of its own, with
     * nothing asserting it, so reverting the three lines that produce it would
     * have left this suite green while {@code /reports?tab=wlascicieli} — one letter out — quietly
     * rendered the arrears board under a URL claiming owner settlements. That failure is silent by
     * construction: the page renders, the data is real, and the only thing naming which report it is
     * appears in a tab label the manager did not read.
     *
     * <p>All three branches in one method rather than the 404 alone, because the value of the
     * assertion is the boundary between them: a change that made every value 404 would break the
     * screen and satisfy a test that only knew about typos, and a change that made every value fall
     * through would restore the original bug and satisfy a test that only knew about the two good
     * values.
     */
    @Test
    void anUnknownTabIs404AndTheTwoKnownValuesStillResolve() throws Exception {
        when(board.forWorkspace(any())).thenReturn(List.of());

        mvc.perform(get("/reports").param("tab", "wlascicieli"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/reports").param("tab", "zaleglosci"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/reports").param("tab", ""))
            .andExpect(status().isNotFound());

        mvc.perform(get("/reports").param("tab", "wlasciciele"))
            .andExpect(status().isOk());
        mvc.perform(get("/reports"))
            .andExpect(status().isOk());
    }

    /**
     * {@code /report} was the arrears board's own URL for as long as it existed, so it is in
     * bookmarks and in anything that linked to it. It redirects rather than 404s.
     */
    @Test
    void theOldReportPathRedirectsToTheReportsScreen() throws Exception {
        mvc.perform(get("/report"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/reports"));
    }
}
