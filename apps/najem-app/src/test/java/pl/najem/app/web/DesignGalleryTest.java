package pl.najem.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import pl.najem.app.web.api.ApiWorkspaceResolver;
import pl.najem.reporting.application.ProjectionStatus;
import pl.najem.um.application.ActingCaller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The gallery renders every component in every state.
 *
 * <p>Cheap, and it catches the one thing a fragment library gets wrong silently: a fragment whose
 * parameters were changed by the screen that needed the change, leaving every other caller
 * rendering an empty element rather than failing. A fragment that is not on this page is not
 * covered by anything.
 *
 * <p>The gallery holds no tenant data and reaches no service — {@link DesignGalleryController}
 * takes no dependency at all — so this is a {@link WebMvcTest} slice around that one controller
 * rather than the full {@code @SpringBootTest} every screen test in this package otherwise uses.
 * No Postgres, no other module's beans: the slice loads Spring MVC infrastructure, autoconfigures
 * Thymeleaf against the real templates on the classpath, and stops there. Task 8 moved it off
 * Testcontainers specifically because a page whose entire design point is that it reaches no
 * service had no business needing a database to render, and because it is the one test that
 * exercises the whole fragment library — the migration's fastest render check was paying the
 * slowest tier's cost for no reason connected to what it actually verifies.
 *
 * <p>{@code najem.um.adapter.security.SecurityConfig} puts Spring Security on the classpath for
 * the whole application (see that class's own javadoc), and a {@code @WebMvcTest} slice
 * autoconfigures Spring Security's default filter chain when no {@code SecurityFilterChain} bean
 * is in the sliced context — which none is here, since slicing does not pull in a
 * {@code @Configuration} from another module's package. Left alone, every request in this class
 * would 401 against a chain the production app never actually runs under test properties. {@code
 * addFilters = false} turns the filter chain off entirely for this slice, which is the right
 * answer for exactly one reason: {@code /design} needs no authentication in any deployment posture
 * — it is "safe for it to be a real route rather than something behind a profile" per
 * {@code DesignGalleryController}'s own javadoc — so a test that runs with no security filter at
 * all is testing the same thing production serves, not granting itself an exemption production
 * does not have.
 *
 * <p>Four beans still need a stand-in, and none is because {@code /design} itself uses them.
 * {@code @WebMvcTest} always includes every {@code @ControllerAdvice} and {@code
 * WebMvcConfigurer} found by {@code NajemApplication}'s own component scan alongside the named
 * controller — not only the ones that apply to it — and {@link ActiveAgencyAdvice}, {@link
 * ProjectionFreshnessAdvice} and {@link WebConfig} (which wires the argument resolvers screens and
 * APIs use to receive a workspace) between them chain to four constructor dependencies:
 * {@link WebWorkspaceResolver}, {@link ProjectionStatus}, {@link ApiWorkspaceResolver} and
 * {@link ActingCaller}. Excluding the three advice/config classes by type was the first thing
 * tried and did not work — {@code WebWorkspaceArgumentResolver} and {@code
 * ActingUserArgumentResolver} are independently discovered as {@code HandlerMethodArgumentResolver}
 * beans regardless of {@link WebConfig}'s own exclusion, since that is one of the categories a
 * {@code @WebMvcTest} slice always keeps — so excluding wrappers chases one dependency chain while
 * leaving a sibling one intact. Mocking the four leaf types directly is what actually terminates
 * every chain at once, however many components end up wired to them: {@code design.html} renders
 * standalone rather than through {@code layout.html} (see that template's own comment) and reads
 * none of the model attributes any of this machinery contributes, so an unstubbed {@link MockBean}
 * is enough — Mockito's default answers are never touched by anything this test asserts.
 */
@WebMvcTest(controllers = DesignGalleryController.class)
@AutoConfigureMockMvc(addFilters = false)
class DesignGalleryTest {

    @Autowired MockMvc mvc;
    @MockBean WebWorkspaceResolver workspaceResolver;
    @MockBean ProjectionStatus projections;
    @MockBean ApiWorkspaceResolver apiWorkspaceResolver;
    @MockBean ActingCaller actingCaller;

    @Test
    void rendersEveryPillTone() throws Exception {
        String html = mvc.perform(get("/design"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Scoped to the element actually carrying data-fragment="pill": a whole-page
        // containsString would also pass if "pill--danger" leaked out of some other fragment's
        // markup (a stray class on a wrapper div, a comment, a caption) rather than the pill
        // itself, which is exactly the kind of drift this gallery exists to catch.
        for (String tone : new String[] {"pill--danger", "pill--warn", "pill--neutral", "pill--paid", "pill--critical"}) {
            assertThat(elementWithFragmentHasClass(html, "pill", tone))
                .as("a <… data-fragment=\"pill\" class=\"…%s…\"> element", tone)
                .isTrue();
        }
    }

    @Test
    void rendersTheStatesTheHandoffNeverDrew() throws Exception {
        // The empty state and a field's own validation error are the two undesigned-but-buildable
        // states the gallery actually renders. Loading and the blocking overlap-check error are
        // NOT built here — see DesignGalleryController's javadoc and the "Nie zaprojektowane"
        // section design.html itself renders — so this test does not look for them.
        String html = mvc.perform(get("/design"))
            .andReturn().getResponse().getContentAsString();

        assertThat(elementWithFragmentHasClass(html, "emptyState", "empty-state"))
            .as("a <… data-fragment=\"emptyState\" class=\"…empty-state…\"> element")
            .isTrue();
        assertThat(elementWithFragmentHasClass(html, "field", "field--error"))
            .as("a <… data-fragment=\"field\" class=\"…field--error…\"> element")
            .isTrue();
    }

    /**
     * Whether some element in {@code html} carries both {@code data-fragment="fragment"} and
     * {@code cssClass} on its own {@code class} attribute — order-independent (Thymeleaf's
     * attribute order is an implementation detail, not something worth pinning), scoped to a
     * single tag so a match can't span two different elements.
     *
     * <p>No HTML parser is on this module's test classpath, so this is a regex rather than a DOM
     * query — deliberately narrow (one tag, both attributes) rather than the whole-page
     * {@code containsString} it replaces, which could not tell a real match from a leak.
     */
    private static boolean elementWithFragmentHasClass(String html, String fragment, String cssClass) {
        Pattern tagWithBoth = Pattern.compile(
            "<[a-zA-Z][^>]*"
                + "(?=[^>]*\\bdata-fragment=\"" + Pattern.quote(fragment) + "\")"
                + "(?=[^>]*\\bclass=\"[^\"]*\\b" + Pattern.quote(cssClass) + "\\b[^\"]*\")"
                + "[^>]*>");
        return tagWithBoth.matcher(html).find();
    }

    /**
     * The 19 fragment names as they actually exist in {@code components.html}, not the list
     * drafted before Task 2 was implemented — verified by reading the file directly.
     *
     * <p>{@code stepRail} joined this list in the task 6b fix round, once property-new.html's two
     * phases and both reserve-*.html screens made its markup a three-times-literal copy —
     * screens.css's own trigger for promoting a class to a shared fragment.
     *
     * <p>{@code arrearsPill} joined it in the final fix round, having been the library's 19th
     * fragment and the only one this map never named — so the class asserted the gallery was
     * complete while the one fragment whose output has a statutory consequence was absent from both.
     * A map that is allowed to be shorter than the library cannot detect the fragment nobody added,
     * which is why {@link #theMapNamesEveryFragmentInTheLibrary} now counts the two against each
     * other rather than trusting this list to be maintained.
     */
    @Test
    void namesEveryFragmentTheLibraryOffersExactlyAsManyTimesAsDesignHtmlIntends() throws Exception {
        String html = mvc.perform(get("/design")).andReturn().getResponse().getContentAsString();

        for (Map.Entry<String, Integer> expected : expectedOccurrences().entrySet()) {
            assertThat(countFragment(html, expected.getKey()))
                .as("data-fragment=\"%s\" must occur exactly %d time(s) in /design's response — a "
                    + "count that drifted from this either means a real fragment was added/removed "
                    + "from design.html (update the expected count here to match) or the fragment "
                    + "renders once and something is now emitting it twice, the way Finding 1's "
                    + "local same-file th:fragment declarations did", expected.getKey(), expected.getValue())
                .isEqualTo(expected.getValue());
        }
    }

    /**
     * Every {@code th:fragment} declared in {@code components.html} is named by
     * {@link #expectedOccurrences()}, and nothing else is.
     *
     * <p>This is the check the count map could not perform on itself. {@code arrearsPill} was the
     * library's 19th fragment, absent from {@code design.html} and absent from the map, and both
     * absences were invisible: the count assertions pass over a fragment they never mention, and the
     * gallery renders fine without it. So the one fragment mapping {@code ArrearsColour} to a pill
     * tone — where {@code BRIGHT_RED} carries an art. 11 consequence — was covered by nothing on the
     * page whose stated purpose is that every fragment is covered.
     *
     * <p>Both directions asserted, because each catches a different mistake: a fragment in the
     * library and not in the map is one nobody demoed, and a name in the map that no longer exists in
     * the library is an assertion quietly counting zero occurrences of nothing and passing.
     */
    @Test
    void theMapNamesEveryFragmentInTheLibrary() throws IOException {
        String source = new String(
            new ClassPathResource("templates/components.html").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);

        Set<String> declared = new LinkedHashSet<>();
        Matcher fragment = Pattern.compile("th:fragment=\"([A-Za-z][\\w]*)").matcher(source);
        while (fragment.find()) {
            declared.add(fragment.group(1));
        }

        assertThat(declared)
            .as("no th:fragment found in components.html — this scan must fail loudly rather than "
                + "silently cover nothing")
            .isNotEmpty();

        assertThat(expectedOccurrences().keySet())
            .as("every fragment components.html declares must be demoed on /design and named in "
                + "expectedOccurrences(), and every name there must still be a real fragment — a "
                + "fragment missing from both is covered by nothing at all, which is exactly how "
                + "arrearsPill went unrendered and unasserted for the whole migration")
            .containsExactlyInAnyOrderElementsOf(declared);
    }

    private static Map<String, Integer> expectedOccurrences() {
        // The count each fragment's data-fragment marker must appear, read off design.html's own
        // content rather than assumed: most fragments demo one state per call and so appear once
        // per gallery example (breadcrumb, progressBar, tabs: one composite demo each), but several
        // legitimately render more than once — several pill tones side by side, several kpi tiles,
        // a table with three pill-bearing rows on top of the five-tone demo (8 total), and so on.
        // A blanket "exactly once" assertion would be wrong for most of this map; a blanket "at
        // least once" (the assertion this replaces) is why Finding 1's duplicate cardBody/cardActions/
        // cardFooter markup survived — "at least once" cannot tell one real render from two.
        Map<String, Integer> expectedOccurrences = new LinkedHashMap<>();
        expectedOccurrences.put("card", 2);          // the two card shapes: full header+footer, body-only
        // Four, not three, since the tiles moved into a real `.kpi-row`: the row is four across and
        // reflows to 2 × 2 at 768, and three tiles would demo that with an empty fourth column.
        expectedOccurrences.put("kpi", 4);            // a full four-across row: 3 tones + a plain tile
        // 13, not 8: the arrearsPill section renders one pill per ArrearsColour on top of the five
        // tones demoed directly and the three in the sample table. arrearsPill's own five renders are
        // counted separately below — the pair is what would catch one of the two sections losing a
        // colour, since a bare total could stay right while one section shrank and the other grew.
        expectedOccurrences.put("pill", 13);          // 5 tones + 3 table rows + 5 from arrearsPill
        expectedOccurrences.put("arrearsPill", 5);    // GOLDEN, GREEN, YELLOW, RED, BRIGHT_RED
        expectedOccurrences.put("chip", 4);           // 2 pill-shape + 2 choice-shape chips
        expectedOccurrences.put("btn", 5);            // 4 in the btn section + 1 inside card 1's actions
        expectedOccurrences.put("field", 7);          // 5 states + the focus-demo pair
        expectedOccurrences.put("selectField", 4);    // 4 selectField states
        expectedOccurrences.put("avatar", 4);         // 4 sizes
        expectedOccurrences.put("keyvalue", 5);       // 3 in its own section + 2 inside card 1's body
        expectedOccurrences.put("checklistItem", 5);  // 3 in its own section + 2 inside card 2's body
        expectedOccurrences.put("progressBar", 1);
        expectedOccurrences.put("railItem", 3);       // 3 timeline states
        expectedOccurrences.put("microLabel", 2);
        expectedOccurrences.put("tabs", 1);
        expectedOccurrences.put("breadcrumb", 1);
        expectedOccurrences.put("emptyState", 2);     // once standalone + once inside the empty table demo
        expectedOccurrences.put("tableHead", 2);      // the populated table + the empty one
        expectedOccurrences.put("stepRail", 2);       // first-step-active + second-step-active
        return expectedOccurrences;
    }

    /**
     * The arrears mapping, rendered: five colours, five sets of words, and {@code BRIGHT_RED} on its
     * own tone.
     *
     * <p>Duplicates {@code ReportsScreenTest}'s assertion deliberately and at a different cost.
     * That class is {@code @Tag("integration")} and boots Postgres, so the only fast-tier evidence
     * that this mapping is intact is here — and this mapping is the one place in the application where
     * a wrong colour is a wrong statement about somebody's tenancy. Asserting the tone is on the
     * element carrying the fragment marker, not merely present in the page, is what stops a
     * {@code pill--critical} appearing in a caption or a neighbouring legend from standing in for it.
     */
    @Test
    void theArrearsPillRendersAllFiveColoursWithTheirOwnToneAndWords() throws Exception {
        String html = mvc.perform(get("/design")).andReturn().getResponse().getContentAsString();

        for (String tone : new String[] {"pill--paid", "pill--warn", "pill--danger", "pill--critical"}) {
            assertThat(elementWithFragmentHasClass(html, "pill", tone))
                .as("arrearsPill's tones must all render: %s", tone)
                .isTrue();
        }

        // The words, one per ArrearsColour, exactly as the fragment decides them. GOLDEN and GREEN
        // share `paid` as a tone, so their words are the only thing telling the two apart on screen.
        assertThat(html).as("GOLDEN").contains("Zapłacone do końca najmu");
        assertThat(html).as("GREEN").contains("Nic nie zalega");
        assertThat(html).as("YELLOW").contains("Termin jeszcze nie minął");
        assertThat(html).as("RED").contains("Po terminie");
        assertThat(html).as("BRIGHT_RED").contains("Cały okres bez zapłaty");

        // No colour fell through to the fragment's `*` case, which would render the enum name itself
        // inside a neutral pill — the failure that looks like a styling glitch rather than a bug.
        for (String name : new String[] {"GOLDEN", "GREEN", "YELLOW", "RED", "BRIGHT_RED"}) {
            assertThat(html)
                .as("%s must not reach arrearsPill's fallback case, which prints the raw enum name",
                    name)
                .doesNotContain(">" + name + "<");
        }
    }

    /** How many times {@code data-fragment="fragment"} occurs in {@code html} — deliberately a raw
     *  count, not "at least one": presence alone cannot tell a single render from a duplicate one,
     *  which is exactly how Finding 1's local fragment duplication went unnoticed. */
    private static int countFragment(String html, String fragment) {
        Matcher matcher = Pattern.compile(Pattern.quote("data-fragment=\"" + fragment + "\""))
            .matcher(html);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * No design value lives in a template — that is TemplateHygieneTest's job (Task 8), and the
     * gallery is the template most likely to acquire one first, so it gets its own tripwire now.
     *
     * <p>Reads the authored template source, not the rendered response: the rendered page
     * legitimately contains {@code style="--avatar-size:28px"} and {@code style="--cols:…"}, both
     * emitted by fragments Task 2 already approved (a dynamic custom property fed a caller value,
     * not a hardcoded design decision). Those are the fragment library's mechanism, not something
     * design.html authored — so the check that means something is on the file this task writes.
     */
    @Test
    void theTemplateItselfCarriesNoDesignValue() throws IOException {
        String source = new String(
            new ClassPathResource("templates/design.html").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);

        assertThat(source).as("design.html must not author its own style= attribute")
            .doesNotContain("style=");
        assertThat(source).as("design.html must not hardcode a hex colour")
            .doesNotContainPattern(Pattern.compile("#[0-9a-fA-F]{3,8}\\b"));
    }
}
