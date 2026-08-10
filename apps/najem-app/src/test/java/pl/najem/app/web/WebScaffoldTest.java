package pl.najem.app.web;

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

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scaffold every later screen renders into. Boots the whole application context on purpose:
 * a module whose beans stop being constructible fails here as well as in e2e, which is the second
 * reason the UI lives in the composition root (najem-build seq 130).
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + WebScaffoldTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class WebScaffoldTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000004";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    pl.najem.um.application.UserService users;
    @Autowired
    pl.najem.um.application.WorkspaceService workspaces;

    /** "/" is the agency screen now, so it needs an agency to be the screen of. */
    static boolean seeded;

    @org.junit.jupiter.api.BeforeEach
    void anAgency() {
        if (seeded) {
            return;
        }
        java.util.UUID operator = users.findBySubject(java.util.UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(java.util.UUID.fromString(OPERATOR),
                java.time.LocalDate.now()));
        workspaces.create("Agencja Testowa", operator, java.time.LocalDate.now());
        seeded = true;
    }

    @Test
    void servesAnHtmlPageAtTheRoot() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void servesHtmxFromTheApplicationRatherThanACdn() throws Exception {
        mvc.perform(get("/vendor/htmx.min.js"))
            .andExpect(status().isOk());
    }

    /**
     * The units screen and the timeline screen it links to, wired end to end: controller, the
     * reporting query they call, and the template. Asked about a property with nothing in it, so
     * no PM events need seeding — what this proves is that the three parts connect and that an
     * empty answer renders as an empty screen rather than an error.
     *
     * <p>Shares this class's context deliberately. Every one of these boots the whole application,
     * and on this machine a second context costs a second Postgres container (najem-build seq 328).
     */
    @Test
    void theUnitsAndTimelineScreensRenderForAPropertyWithNothingInIt() throws Exception {
        java.util.UUID nothing = java.util.UUID.randomUUID();

        mvc.perform(get("/properties/" + nothing + "/units"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("nie ma jeszcze")));

        mvc.perform(get("/tenancies/" + nothing + "/timeline"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Brak zapisów")));
    }

    /**
     * Every screen says which agency it is showing, not only the home page. Once a person can
     * belong to several and switch, a screen that stays silent about it invites acting in the
     * wrong one — and a misdirected write never collides with the correct one, so nothing tells
     * them. Asserted on a screen that is not the home page, because that is the case that was
     * missing.
     */
    @Test
    void everyScreenNamesTheAgencyItIsShowing() throws Exception {
        mvc.perform(get("/properties"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Agencja Testowa")));
    }

    /**
     * A page that pulls its script from a third party is a page whose render depends on that party
     * being reachable, in an application handling tenancy-scoped financial data (plan decision E).
     */
    @Test
    void theLayoutReferencesNoExternalHost() throws Exception {
        String html = mvc.perform(get("/"))
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
            .doesNotContain("http://")
            .doesNotContain("https://");
    }

    /**
     * The nav has the shape the design gives it, and no item is a link to nothing.
     *
     * <p>Two of the seven destinations are not built. They render muted and non-clickable rather
     * than being omitted, which is a deliberate departure from home.html's "only what is wired is
     * a link" — honoured in substance, since nothing here is a link to nothing.
     *
     * <p><b>It was three until Najmy was built.</b> This test went red on that change, which is the
     * whole reason it names the unbuilt items individually rather than counting them: an item
     * quietly losing its link is the same diff as an item quietly gaining one, and only the
     * assertion below can tell those apart.
     *
     * <p><b>Seven, and "Zaległości" rather than "Raporty".</b> Prototype v2 rebuilt the rail: three
     * groups instead of two, Płatności dropped, and Raporty promoted from an item inside KSIĘGOWOŚĆ
     * to the group heading its own template comment already said it was — with the report itself,
     * Zaległości, as the item beneath it. Same route; the rail now names the thing on the screen
     * rather than the category it belongs to.
     *
     * <p>Asserted by label rather than by count, because a count is the one thing a template can
     * satisfy while showing the wrong seven things. Płatności is asserted absent for the same
     * reason the two reports are: an item that was deliberately removed coming back is a change
     * nobody would otherwise notice, since it would look exactly like the design it replaced.
     *
     * <p><b>Scoped to the {@code <aside>}, and that is the point of {@link #sidebarOf}.</b> This
     * assertion used to run against the whole page and was passing for the wrong reason: the rail's
     * "Raporty" item had already been renamed to "Zaległości", and the check stayed green because
     * home.html renders a "Raporty" destination card in {@code <main>}. So a test whose javadoc
     * claimed it could not be satisfied while the rail showed the wrong things was being satisfied
     * by markup outside the rail entirely. Every word below is common enough to appear somewhere on
     * a page — "Bank", "Nieruchomości" and "Raporty" are all destinations on the home screen — which
     * makes a whole-page {@code contains} the wrong instrument regardless of which labels it names.
     */
    @Test
    void theSidebarShowsAllSevenDestinationsAndLinksOnlyTheBuiltOnes() throws Exception {
        String html = mvc.perform(get("/"))
            .andReturn().getResponse().getContentAsString();
        String rail = sidebarOf(html);

        /*
          The VISIBLE labels, exactly and in order — not a substring search over the rail's markup.

          Scoping to the <aside> was not enough on its own. Every item carries `title="…"` as well as
          its `.nav__label`, so a rail whose visible word had been changed back to "Raporty" still
          contained the string "Zaległości" in an attribute, and a `contains` check stayed green.
          Proved by mutation, which is the only reason it was found: renaming the label and running
          this test passed twice before the assertion was narrowed to the label element itself.

          containsExactly also does the two jobs the old javadoc wanted from a label check and a
          count check at once — a missing item, an extra one, and a reordering are each a failure.
        */
        org.assertj.core.api.Assertions.assertThat(navLabelsOf(rail))
            .containsExactly("Pulpit", "Nieruchomości", "Najmy",
                "Faktury", "Bank", "Księga główna",
                "Zaległości");
        // Unbuilt: rendered, muted, and carrying no href. Faktury and Księga główna are the two
        // left — named rather than counted, so that a THIRD item silently becoming unbuilt (a
        // regression that would look like nothing in a diff) fails here.
        org.assertj.core.api.Assertions.assertThat(unbuiltLabelsOf(rail))
            .containsExactly("Faktury", "Księga główna");
        // And Najmy is a real link now, which is what makes the register reachable at all.
        org.assertj.core.api.Assertions.assertThat(rail).contains("href=\"/tenancies\"");
        // The active item is distinguishable by more than colour alone (weight 600, per the
        // handoff) — asserted here as the class actually landing, not merely as a CSS rule that
        // could be dead: Thymeleaf 3.1 dropped #httpServletRequest, so the comparison this class
        // depends on runs through a model attribute instead, and that wiring deserves its own
        // check rather than trust.
        org.assertj.core.api.Assertions.assertThat(rail).contains("nav__item--active");
    }

    /**
     * The visible labels of the items that are NOT links — the {@code <span class="nav__item">}s.
     *
     * <p>Reads the labels rather than counting {@code nav__item--unbuilt} occurrences, for the same
     * reason {@link #navLabelsOf} exists: a count cannot tell which items are muted, and "three
     * unbuilt" stayed true for a while across two different sets of three.
     */
    private static java.util.List<String> unbuiltLabelsOf(String rail) {
        var labels = new java.util.ArrayList<String>();
        var matcher = java.util.regex.Pattern
            .compile("<span class=\"nav__item nav__item--unbuilt\".*?</span>\\s*</span>",
                java.util.regex.Pattern.DOTALL)
            .matcher(rail);
        while (matcher.find()) {
            var label = java.util.regex.Pattern
                .compile("<span class=\"nav__label\">(.*?)</span>")
                .matcher(matcher.group());
            if (label.find()) {
                labels.add(label.group(1).trim());
            }
        }
        return labels;
    }

    /**
     * The rail's own markup, without the rest of the page.
     *
     * <p>Crude on purpose, in the same spirit as AddPropertyScreenTest's {@code mainOf}: a real
     * parser would be a dependency, and the only question here is "which words are inside the
     * {@code <aside>}". Fails loudly rather than returning the whole page if the element cannot be
     * found — a fallback to the full HTML is exactly the silent widening this helper exists to undo.
     */
    private static String sidebarOf(String html) {
        var matcher = java.util.regex.Pattern.compile("(?s)<aside\\b.*?</aside>").matcher(html);
        if (!matcher.find()) {
            throw new AssertionError("the page rendered no <aside> — the rail is what this asserts on");
        }
        return matcher.group();
    }

    /**
     * The words a manager actually reads in the rail, in document order.
     *
     * <p>{@code .nav__label} only: {@code title} is a tooltip and the group headings (KSIĘGOWOŚĆ,
     * RAPORTY) are not destinations. Reading the label element rather than the rail's raw text is
     * what makes the assertion above about what is on the screen instead of about what is anywhere
     * in the markup.
     */
    private static List<String> navLabelsOf(String rail) {
        var labels = new java.util.ArrayList<String>();
        var matcher = java.util.regex.Pattern
            .compile("<span class=\"nav__label\">([^<]*)</span>").matcher(rail);
        while (matcher.find()) {
            labels.add(matcher.group(1).trim());
        }
        if (labels.isEmpty()) {
            throw new AssertionError("no .nav__label in the rail — this assertion would pass unchecked");
        }
        return labels;
    }

    /**
     * Polish is not in the latin subset. A vendored font that ships only `latin` renders
     * "Nieruchomości" with the ś and ć from a fallback face — a visible change of typeface
     * mid-word that nobody notices on an English test fixture.
     */
    @Test
    void servesTheFontsItNeedsIncludingPolishGlyphs() throws Exception {
        for (String face : new String[] {
            "instrument-sans-variable-latin-ext.woff2",
            "instrument-sans-variable-latin.woff2",
            "ibm-plex-mono-400-latin-ext.woff2",
            "ibm-plex-mono-500-latin-ext.woff2"
        }) {
            mvc.perform(get("/vendor/fonts/" + face))
                .andExpect(status().isOk());
        }
    }
}
