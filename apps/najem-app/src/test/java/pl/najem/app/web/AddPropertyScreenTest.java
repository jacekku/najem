package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactService;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.Property;
import pl.najem.reporting.application.ProjectionRunner;
import pl.najem.reporting.application.UnitBoardQuery;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adding a property through the screen a manager actually uses — phase one, address and owners.
 *
 * <p>Container tier, modelled on {@link ReserveScreenTest} and {@link AddUnitScreenTest}: real
 * Postgres via {@code @ServiceConnection}, {@code permit-all}, and projections drained explicitly
 * where the test needs one.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + AddPropertyScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class AddPropertyScreenTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000040";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired PortfolioService portfolio;
    @Autowired ProjectionRunner projections;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventStore store;
    @Autowired UnitBoardQuery units;
    @Autowired ContactService contacts;

    /** Static: the agency is shared fixture, same reasoning as ReserveScreenTest and AddUnitScreenTest. */
    static UUID agency;

    UUID workspaceId;
    UUID otherWorkspaceId;

    @BeforeEach
    void anAgencyAndAStranger() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency == null) {
            agency = workspaces.create("Agencja Nieruchomości", operator, LocalDate.now());
        }
        workspaceId = agency;

        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        otherWorkspaceId = workspaces.create("Cudza agencja (nieruchomości)", stranger, LocalDate.now());
    }

    @Test
    void theportfolioBoardOffersTheAddButton() throws Exception {
        mvc.perform(get("/properties"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Dodaj nieruchomość")))
            .andExpect(content().string(containsString("/properties/new")));
    }

    /**
     * The regression test for fix round 1's item 1: a {@code th:if} on the same element as its
     * {@code th:replace} never guards anything — Thymeleaf resolves {@code th:replace} at
     * precedence 100 and {@code th:if} at 300, so the fragment substitutes the element before the
     * guard is ever evaluated, and the empty state rendered unconditionally, including above
     * populated rows. That shipped once already, and a full green suite did not catch it: nothing
     * asserted the empty state's ABSENCE when properties exist, only its presence when they do not.
     */
    @Test
    void thepropertiesListDoesNotShowTheEmptyStateWhenPropertiesExist() throws Exception {
        UUID owner = contacts.registerParty(workspaceId,
            new ContactDetails("Ewa", "Portfelska", "ewa.portfelska@example.com", null),
            true, LocalDate.now());

        mvc.perform(post("/properties").with(csrf())
                .param("address", "Portfelowa 1, Warszawa")
                .param("owner", owner.toString()).param("share", "100"))
            .andExpect(status().is3xxRedirection());

        projections.runOnce();

        mvc.perform(get("/properties"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Portfelowa 1, Warszawa")))
            .andExpect(content().string(
                not(containsString("Ta agencja nie ma jeszcze żadnych nieruchomości."))));
    }

    /**
     * The address field's placeholder — the only concrete example of the expected format
     * ("np. Krucza 12/4, Warszawa") a manager has, and lost silently once already when the
     * {@code field()} fragment's reskin dropped every placeholder it was not given a parameter for.
     * Pinned so a future signature change to {@code field()} cannot drop it again without this
     * test noticing — an unasserted placeholder is one refactor away from vanishing, which is
     * exactly how it vanished this time.
     */
    @Test
    void theaddressFieldKeepsItsExampleFormatPlaceholderAndAutofocus() throws Exception {
        String html = mvc.perform(get("/properties/new"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        java.util.regex.Matcher tag = java.util.regex.Pattern
            .compile("<input[^>]*\\bname=\"address\"[^>]*>")
            .matcher(html);
        assertThat(tag.find()).as("an <input name=\"address\"> element").isTrue();
        assertThat(tag.group())
            .contains("placeholder=\"np. Krucza 12/4, Warszawa\"")
            .contains("autofocus");
    }

    /**
     * The picker fragment must appear EXACTLY ONCE. Asserted as a count, not as presence, because
     * the bug this catches — a th:fragment declared inline rendering both where it sits and where it
     * is inserted — is completely invisible to a present/absent assertion, and shipped once already.
     */
    @Test
    void theownerPickerIsRenderedOnce() throws Exception {
        String html = mvc.perform(get("/properties/new"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html.split("— albo nowy właściciel —", -1)).hasSize(2);
    }

    /**
     * NOT ONE PHASE-ONE BUTTON PUTS THE SESSION'S CSRF TOKEN IN A URL.
     *
     * <p>It did. Phase one is a single form whose own method is {@code post}, so Thymeleaf's Spring
     * dialect injects a hidden {@code _csrf} input — and the four non-creating buttons carried
     * {@code formmethod="get"}, which serialises <em>every</em> field of the form into the query
     * string. Clicking Szukaj produced
     * {@code /properties/new?_csrf=SjuGvl7…&address=…&q=Now}, putting the token in the address bar,
     * the history, any bookmark, and every proxy and container access log on the way. The default
     * repository here is {@code HttpSessionCsrfTokenRepository} behind
     * {@code XorCsrfTokenRequestAttributeHandler}, so a leaked masked token yields the raw one for
     * as long as the session lives.
     *
     * <p>Asked of every button rather than of the four that were wrong, because the next button
     * somebody adds is the one nobody remembers to check. The token's presence is asserted first: a
     * form with no {@code _csrf} field would satisfy every {@code doesNotContain} below while being
     * a worse bug.
     *
     * <p>Proven by mutation: putting {@code formmethod="get"} back on Szukaj turns this red.
     */
    @Test
    void nophaseOneButtonPutsTheCsrfTokenInAUrl() throws Exception {
        UUID owner = contacts.registerParty(workspaceId,
            new ContactDetails("Zofia", "Tokenowa", "zofia.tokenowa@example.com", null),
            true, LocalDate.now());

        // Drafted owner AND a matching search term, so Usuń, the hit button and "Dalej" are all on
        // the page — a page rendered with nothing drafted shows two of the five and would let three
        // regressions through.
        String page = mvc.perform(get("/properties/new")
                .param("address", "Krucza 12/4")
                .param("owner", owner.toString())
                .param("q", "Tokenowa"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String form = formContaining(page, "Szukaj");
        assertThat(fieldsOf(form)).containsKey("_csrf");

        var buttons = buttons(form);
        assertThat(buttons).as("Usuń, Szukaj, the hit, Dodaj and Dalej").hasSize(5);
        for (String button : buttons) {
            assertThat(urlProducedBy(form, button))
                .as("the URL the address bar would hold after clicking %s", button)
                .doesNotContain("_csrf");
        }
    }

    /**
     * And the form carries the token at all.
     *
     * <p>{@code _csrf} appeared in no test and no template in this repository, while the whole shape
     * of phase one depends on the form's own method being POST. A future tidy-up that "corrected"
     * {@code method="post"} to {@code get} — tempting once every button is a POST with its own
     * formaction — would 403 every inline owner creation and leave every test in this class green,
     * because they all reach the POST endpoints with {@code .with(csrf())} rather than through the
     * rendered page.
     */
    @Test
    void thephaseOneFormCarriesACsrfToken() throws Exception {
        String page = mvc.perform(get("/properties/new"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String form = formContaining(page, "Dodaj");
        assertThat(attribute(form, "method")).isEqualToIgnoringCase("post");
        assertThat(fieldsOf(form)).containsKey("_csrf");
        assertThat(fieldsOf(form).get("_csrf")).isNotBlank();
    }

    /**
     * Drafting an owner does not demand the address yet — on the browser side or the server side.
     *
     * <p>The address input was {@code required} and "Dodaj" is the one button without
     * {@code formnovalidate}, so collapsing phase one into a single form quietly made the address
     * mandatory at DRAFT time in the browser, while the server accepted a blank one at that same
     * endpoint. An address is mandatory when the property is created, which is phase two. Both
     * halves are asserted because either alone leaves the inconsistency reachable from the other
     * side.
     */
    @Test
    void draftingAnOwnerDoesNotRequireTheAddressYet() throws Exception {
        String page = mvc.perform(get("/properties/new"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(inputNamed(formContaining(page, "Dodaj"), "address")).doesNotContain("required");

        mvc.perform(post("/properties/new/owners").with(csrf())
                .param("address", "")
                .param("givenName", "Halina").param("surname", "Przedadresowa")
                .param("email", "halina.przedadresowa@example.com").param("infoClauseServed", "true"))
            .andExpect(status().is3xxRedirection());
    }

    /**
     * The typed address survives a search — driven through the RENDERED FORM, which is the only way
     * this can be asked.
     *
     * <p>The version this replaces did {@code get("/properties/new").param("address", …)} and
     * asserted the address came back. That put the address into the request itself, which is the
     * case that was never at risk: it proved the model round-trips a query parameter and said
     * nothing about the three sibling forms that were the actual defect. Live, typing an address and
     * clicking Szukaj produced {@code ?address=&q=Now} and a blank box, because the hidden copy on
     * the search form held what the SERVER last knew.
     *
     * <p>So this parses the page, takes the fields of the form the Szukaj button belongs to, types
     * an address into the one the manager can see, and submits exactly those fields to exactly the
     * target that button names. If the address input is not in that form there is nothing to type
     * into and the assertion cannot be satisfied — which is the mutation this test has to survive.
     *
     * <p>Driven over ONE session, submitting the token the page itself rendered rather than
     * {@code .with(csrf())}. That is what a browser does, and it is the only version of this that
     * would notice the form losing its {@code method="post"} — every other test here reaches these
     * endpoints with a synthesised token and would stay green through that.
     */
    @Test
    void searchingForAnOwnerCarriesTheAddressTheManagerJustTyped() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        String page = mvc.perform(get("/properties/new").session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String form = formContaining(page, "Szukaj");
        var fields = fieldsOf(form);
        assertThat(fields).containsKey("address");
        // And it is the one the manager can SEE. Everything below would be satisfied by a hidden
        // copy — the browser submits those too — while the copy carries what the server last knew
        // rather than what was just typed, which is exactly the defect. Without this line the
        // mutation that puts the visible input back in a sibling form stays green.
        assertThat(inputNamed(form, "address")).contains("type=\"text\"");

        // The manager types. Ampersand on purpose: it is also what I5's redirect used to truncate,
        // and every other address in this suite is one with nothing that needs encoding.
        fields.put("address", "Kwiatowa 1 & 3, Sopot");
        fields.put("q", "Nikt");

        // POSTed, because that is what the button now does — and the token travels in the body,
        // which is the whole point of the change. `fields` still holds the _csrf the page rendered.
        var search = post(submitTarget(form, "Szukaj")).session(session);
        fields.forEach(search::param);

        String redirect = mvc.perform(search).andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();
        assertThat(redirect).doesNotContain("_csrf");

        String after = followRedirect(redirect).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(fieldsOf(formContaining(after, "Szukaj")))
            .containsEntry("address", "Kwiatowa 1 & 3, Sopot")
            .containsEntry("q", "Nikt");
    }

    /**
     * The structural half of the same claim, asserted directly so a regression names itself rather
     * than showing up as a lost value three steps downstream. One form, one address input, and the
     * button that searches is inside it.
     */
    @Test
    void phaseOneIsOneFormWithOneVisibleAddressInput() throws Exception {
        String page = mvc.perform(get("/properties/new"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(forms(page)).hasSize(1);
        assertThat(mainOf(page).split("name=\"address\"", -1)).hasSize(2);
        assertThat(page).doesNotContain("Zapamiętaj adres");
        assertThat(formContaining(page, "Szukaj")).contains("name=\"address\"");
        assertThat(formContaining(page, "Dodaj")).contains("name=\"address\"");
    }

    // ── Reading the rendered page the way a browser would ────────────────────────────────────
    //
    // Crude on purpose: a real parser would be a dependency, and what these need to know is only
    // "which fields would this button submit", which is a question about text.

    /**
     * The screen's own markup, without the layout's. The masthead carries a search form whose input
     * is placeholdered "Szukaj" and a theme form that posts, so "the forms on this page" is three
     * before it is one — and a check that phase one is a single form has to be a check about phase
     * one.
     */
    private static String mainOf(String html) {
        var matcher = java.util.regex.Pattern.compile("(?s)<main\\b.*?</main>").matcher(html);
        if (!matcher.find()) {
            throw new AssertionError("the page rendered no <main>");
        }
        return matcher.group();
    }

    private static List<String> forms(String html) {
        var forms = new java.util.ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("(?s)<form\\b.*?</form>").matcher(mainOf(html));
        while (matcher.find()) {
            forms.add(matcher.group());
        }
        return forms;
    }

    private static String formContaining(String html, String label) {
        return forms(html).stream().filter(f -> f.contains(label)).findFirst()
            .orElseThrow(() -> new AssertionError("no <form> on the page contains " + label));
    }

    /** What the browser would serialise: every named input, its value or the empty string. */
    private static java.util.LinkedHashMap<String, String> fieldsOf(String form) {
        var fields = new java.util.LinkedHashMap<String, String>();
        var input = java.util.regex.Pattern.compile("(?s)<input\\b[^>]*>").matcher(form);
        while (input.find()) {
            String tag = input.group();
            if (tag.contains("type=\"checkbox\"") || tag.contains("type=\"radio\"")) {
                continue;
            }
            String name = attribute(tag, "name");
            if (name != null) {
                fields.put(name, unescape(attribute(tag, "value") == null ? "" : attribute(tag, "value")));
            }
        }
        return fields;
    }

    private static String inputNamed(String form, String name) {
        var input = java.util.regex.Pattern.compile("(?s)<input\\b[^>]*>").matcher(form);
        while (input.find()) {
            if (name.equals(attribute(input.group(), "name"))) {
                return input.group();
            }
        }
        throw new AssertionError("this form has no input named " + name);
    }

    /** Every submit button in this form, tag and label together. */
    private static List<String> buttons(String form) {
        var found = new java.util.ArrayList<String>();
        var button = java.util.regex.Pattern.compile("(?s)<button\\b[^>]*>.*?</button>").matcher(form);
        while (button.find()) {
            found.add(button.group());
        }
        return found;
    }

    /**
     * The URL a browser's address bar would end up holding after this button is clicked — which is
     * where a leaked token is readable, and what history, bookmarks and access logs keep.
     *
     * <p>A GET submission serialises every field of the whole form into the query string, the button
     *'s own name/value included; a POST submission puts them in the body and leaves the URL at the
     * bare action. Modelling the mechanism rather than the outcome (refactoring rule 14): a test that
     * simply asserted "every button is a POST" would be a restatement of the fix, whereas this
     * computes what the browser would actually produce and looks in it.
     */
    private static String urlProducedBy(String form, String button) {
        String action = attribute(button, "formaction");
        action = unescape(action != null ? action : attribute(form, "action"));

        String method = attribute(button, "formmethod");
        method = method != null ? method : attribute(form, "method");
        if (!"get".equalsIgnoreCase(method)) {
            return action;
        }

        var fields = fieldsOf(form);
        String name = attribute(button, "name");
        if (name != null) {
            String value = attribute(button, "value");
            fields.put(name, unescape(value == null ? "" : value));
        }
        var query = new StringBuilder();
        fields.forEach((key, value) ->
            query.append(query.isEmpty() ? "?" : "&").append(key).append("=").append(value));
        return action + query;
    }

    /**
     * A GET of a redirect, issued the way a servlet container would: the query string decoded as
     * {@code application/x-www-form-urlencoded} before it reaches a {@code @RequestParam}.
     *
     * <p>Handing MockMvc the raw location instead would be a test about MockMvc — its own query
     * parsing percent-decodes but leaves a literal '+' alone, so an address encoded by
     * {@code URLEncoder} comes back with plus signs where the spaces were and every assertion
     * downstream is about the wrong thing.
     */
    private org.springframework.test.web.servlet.ResultActions followRedirect(String location)
        throws Exception {
        var uri = UriComponentsBuilder.fromUriString(location).build();
        var request = get(uri.getPath());
        // A bare `name` with no `=` reaches a servlet as a parameter whose value is the empty
        // string, not as an absent one — and that difference is a live defect, not a curiosity:
        // OwnerDraft refuses "" and would 400 the page. Modelled rather than skipped (rule 14).
        uri.getQueryParams().forEach((name, values) -> values.forEach(value ->
            request.param(name, value == null ? "" : URLDecoder.decode(value, StandardCharsets.UTF_8))));
        return mvc.perform(request);
    }

    /** Where a given submit button sends the form — its own formaction, or the form's action. */
    private static String submitTarget(String form, String label) {
        var button = java.util.regex.Pattern.compile("(?s)<button\\b[^>]*>.*?</button>").matcher(form);
        while (button.find()) {
            if (button.group().contains(label)) {
                String formaction = attribute(button.group(), "formaction");
                return unescape(formaction != null ? formaction : attribute(form, "action"));
            }
        }
        throw new AssertionError("no <button> in this form is labelled " + label);
    }

    private static String attribute(String tag, String name) {
        // The lookbehind matters: a bare action="…" pattern also matches inside formaction="…".
        var matcher = java.util.regex.Pattern.compile("(?<![\\w-])" + name + "=\"([^\"]*)\"").matcher(tag);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&amp;", "&");
    }

    @Test
    void anownerNobodyHasMetYetIsCreatedInlineAndAppearsInTheDraft() throws Exception {
        String redirect = mvc.perform(post("/properties/new/owners").with(csrf())
                .param("address", "Krucza 12/4")
                .param("givenName", "Anna").param("surname", "Kowalska")
                .param("email", "anna@example.com").param("infoClauseServed", "true"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).startsWith("/properties/new").contains("owner=");
        assertThat(redirect).doesNotContain("_csrf");

        followRedirect(redirect)
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Anna Kowalska")))
            .andExpect(content().string(containsString("Krucza 12/4")));

        // Rule 12: the lawful basis is stored data and the value is the point of the decision.
        assertThat(jdbc.queryForObject(
            "select lawful_basis from contacts_person where workspace_id = ? and surname = ?",
            String.class, workspaceId, "Kowalska")).isEqualTo("contract");
    }

    @Test
    void thesamePersonCannotBeDraftedAsOwnerTwice() throws Exception {
        // Own surname/email, distinct from anownerNobodyHasMetYetIsCreatedInlineAndAppearsInTheDraft
        // and thenextStepIsOfferedOnlyOnceSomebodyOwnsTheProperty: all three tests register into the
        // same static shared workspace, and a shared surname would make the other test's
        // "select ... where workspace_id = ? and surname = ?" match more than one row.
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Bibiana", "Duplikat", "bibiana.duplikat@example.com", null),
            true, LocalDate.now());

        mvc.perform(get("/properties/new")
                .param("address", "Krucza 12/4")
                .param("owner", anna.toString())
                .param("owner", anna.toString()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void agarbledOwnerIdIsRefusedRatherThanSilentlyDropped() throws Exception {
        mvc.perform(get("/properties/new").param("owner", "not-a-uuid"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void thenextStepIsOfferedOnlyOnceSomebodyOwnsTheProperty() throws Exception {
        mvc.perform(get("/properties/new").param("address", "Krucza 12/4"))
            .andExpect(content().string(not(containsString("Dalej — udziały"))));

        // Own surname/email, distinct for the same reason given in
        // thesamePersonCannotBeDraftedAsOwnerTwice.
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Celina", "Udziałowa", "celina.udzialowa@example.com", null),
            true, LocalDate.now());

        mvc.perform(get("/properties/new").param("address", "Krucza 12/4")
                .param("owner", anna.toString()))
            .andExpect(content().string(containsString("Dalej — udziały")));
    }

    // The pairs below use a distinct surname per test, same reasoning as
    // thesamePersonCannotBeDraftedAsOwnerTwice and thenextStepIsOfferedOnlyOnceSomebodyOwnsTheProperty:
    // every test in this class registers into the same static shared workspace, and a shared surname
    // would make another test's "select ... where workspace_id = ? and surname = ?" match more than
    // one row (as anownerNobodyHasMetYetIsCreatedInlineAndAppearsInTheDraft's does).

    @Test
    void apropertyWithTwoOwnersAtFiftyFiftyIsCreatedWithoutWarnings() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Połowska", "anna.polowska@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Połowski", "piotr.polowski@example.com", null), true, LocalDate.now());

        String redirect = mvc.perform(post("/properties").with(csrf())
                .param("address", "Krucza 12/4, Warszawa")
                .param("owner", anna.toString()).param("share", "50")
                .param("owner", piotr.toString()).param("share", "50"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).matches("/properties/[0-9a-f-]{36}/units");

        projections.runOnce();
        mvc.perform(get(redirect))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Dodaj lokal")));

        assertThat(jdbc.queryForObject("select count(*) from pm_property where address = ?",
            Integer.class, "Krucza 12/4, Warszawa")).isEqualTo(1);
    }

    /**
     * Created ANYWAY, and warned. Asserting only the warning would pass equally against an
     * implementation that refused the write, which is the opposite of the decision taken.
     */
    @Test
    void sharesThatDoNotAddUpStillCreateThePropertyAndSaySo() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Wilcza", "anna.wilcza@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Wilczy", "piotr.wilczy@example.com", null), true, LocalDate.now());

        var result = mvc.perform(post("/properties").with(csrf())
                .param("address", "Wilcza 3")
                .param("owner", anna.toString()).param("share", "60")
                .param("owner", piotr.toString()).param("share", "30"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        @SuppressWarnings("unchecked")
        var warnings = (List<String>) result.getFlashMap().get("warnings");
        assertThat(warnings).singleElement().asString().contains("90");

        assertThat(jdbc.queryForObject("select count(*) from pm_property where address = ?",
            Integer.class, "Wilcza 3")).isEqualTo(1);
    }

    @Test
    void thesharesPhaseListsEveryDraftedOwnerWithAnInput() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Udziałowska", "anna.udzialowska@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Udziałowski", "piotr.udzialowski@example.com", null), true, LocalDate.now());

        String html = mvc.perform(get("/properties/new")
                .param("address", "Krucza 12/4")
                .param("owner", anna.toString()).param("owner", piotr.toString())
                .param("owners", "done"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Anna Udziałowska").contains("Piotr Udziałowski");
        assertThat(html.split("name=\"share\"", -1)).hasSize(3);
        // The even split is prefilled as a suggestion.
        assertThat(html).contains("value=\"50.00\"");
        // Phase two shows no picker — the owner list is settled.
        assertThat(html).doesNotContain("— albo nowy właściciel —");
    }

    /**
     * "Dalej — udziały" end to end over Post/Redirect/Get: the POST validates the draft, the
     * redirect names phase two, and the GET renders it. Asserted through the button's own route
     * because {@code restate}'s {@code owners=done} branch is otherwise reached only by tests that
     * type the URL themselves, which is the one caller that cannot regress.
     */
    @Test
    void thedalejButtonPostsAndLandsOnTheSharesPhase() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Dalejowa", "anna.dalejowa@example.com", null),
            true, LocalDate.now());

        String redirect = mvc.perform(post("/properties/new").with(csrf())
                .param("address", "Dalejowa 5")
                .param("owner", anna.toString())
                .param("owners", "done"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).doesNotContain("_csrf").contains("owners=done");

        followRedirect(redirect).andExpect(status().isOk())
            .andExpect(content().string(containsString("Udziały")))
            .andExpect(content().string(containsString("Dalejowa 5")));
    }

    @Test
    void reachingTheSharesPhaseWithNobodyDraftedGoesBackAndSaysWhy() throws Exception {
        mvc.perform(get("/properties/new").param("address", "Krucza 12/4").param("owners", "done"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("co najmniej jednego właściciela")))
            .andExpect(content().string(containsString("— albo nowy właściciel —")));
    }

    @Test
    void ashareListShorterThanTheOwnerListIsRefused() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Krótka", "anna.krotka@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Krótki", "piotr.krotki@example.com", null), true, LocalDate.now());

        mvc.perform(post("/properties").with(csrf())
                .param("address", "Krucza 12/4")
                .param("owner", anna.toString()).param("owner", piotr.toString())
                .param("share", "100"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void anownerFromAnotherAgencyIsNotFound() throws Exception {
        UUID theirs = contacts.registerParty(otherWorkspaceId,
            new ContactDetails("Nie", "Nasza", "x@example.com", null), true, LocalDate.now());

        mvc.perform(post("/properties").with(csrf())
                .param("address", "Krucza 12/4")
                .param("owner", theirs.toString()).param("share", "100"))
            .andExpect(status().isNotFound());
    }

    /**
     * WHICH owner got WHICH share, read back off the stream.
     *
     * <p>Nothing else could tell a correct pairing from a reversed one. 50/50 is
     * permutation-invariant, and the 60/30 test asserts only that the warning names the sum 90,
     * which is also permutation-invariant. That matters more than usual here because nothing in the
     * application ever reads a property's owners back — Reporting's PropertyProjection keeps the
     * address and there is no owners screen — so a mispairing would be written to the event stream
     * and never surface anywhere.
     *
     * <p>Proven by mutation: with {@code shares.get(shares.size() - 1 - i)} in the controller this
     * goes red on the first share and green again on revert.
     */
    @Test
    void eachOwnerGetsTheShareTypedBesideTheirOwnName() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Parzysta", "anna.parzysta@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Nieparzysty", "piotr.nieparzysty@example.com", null), true, LocalDate.now());

        String redirect = mvc.perform(post("/properties").with(csrf())
                .param("address", "Parzysta 7")
                .param("owner", anna.toString()).param("share", "60")
                .param("owner", piotr.toString()).param("share", "30"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        UUID propertyId = UUID.fromString(redirect.replaceAll(".*/properties/([^/]+)/units", "$1"));
        var owners = Property.from(store.load(propertyId, "Property").events()).owners();

        assertThat(owners).extracting(Owner::contactId).containsExactly(anna, piotr);
        assertThat(owners.get(0).sharePercent()).isEqualByComparingTo("60");
        assertThat(owners.get(1).sharePercent()).isEqualByComparingTo("30");
    }

    /**
     * A present-but-empty share. Spring binds {@code share=} to a <em>null element</em>, so the list
     * is the right length, the controller's length check passes, and the null used to reach
     * {@code Property.warnings()} — {@code reduce(ZERO, BigDecimal::add)} over a null, an NPE, a 500
     * on a form somebody mistyped. The domain guard makes it a 400, which is what it is.
     */
    @Test
    void ashareLeftEmptyIsRefusedAsABadRequestRatherThanCrashing() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Pusta", "anna.pusta@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Pusty", "piotr.pusty@example.com", null), true, LocalDate.now());

        mvc.perform(post("/properties").with(csrf())
                .param("address", "Krucza 12")
                .param("owner", anna.toString()).param("share", "50")
                .param("owner", piotr.toString()).param("share", ""))
            .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("select count(*) from pm_property where address = ?",
            Integer.class, "Krucza 12")).isZero();
    }

    @Test
    void anegativeShareIsRefusedAsABadRequest() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Ujemna", "anna.ujemna@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Ujemny", "piotr.ujemny@example.com", null), true, LocalDate.now());

        mvc.perform(post("/properties").with(csrf())
                .param("address", "Ujemna 1")
                .param("owner", anna.toString()).param("share", "-10")
                .param("owner", piotr.toString()).param("share", "110"))
            .andExpect(status().isBadRequest());
    }

    /**
     * An address with an ampersand, through the inline-create redirect.
     *
     * <p>{@code UriComponentsBuilder…build().toUriString()} does not encode, and the pattern was
     * copied from ReserveScreenController where every value is a UUID or a literal. Live, "Kwiatowa
     * 1 &amp; 3, Sopot" came back as "Kwiatowa 1 " — the browser read the ampersand as the start of
     * the next parameter. Every address in this suite before this test was "Krucza 12/4" or similar:
     * the only kind with no character that needs encoding, which is why it was never caught.
     */
    @Test
    void anaddressWithAnAmpersandSurvivesAddingAnOwner() throws Exception {
        String address = "Kwiatowa 1 & 3, Sopot #2 + oficyna";

        String redirect = mvc.perform(post("/properties/new/owners").with(csrf())
                .param("address", address)
                .param("givenName", "Ewa").param("surname", "Ampersandowa")
                .param("email", "ewa.ampersandowa@example.com").param("infoClauseServed", "true"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        // Asserted on the redirect itself, decoded the way a servlet container decodes a query
        // string. Re-fetching it through MockMvc would prove nothing either way: MockMvc's own
        // query parsing does not percent-decode, so it hands the controller the escapes verbatim
        // and the round trip cannot distinguish an encoded redirect from a broken one.
        String carried = UriComponentsBuilder.fromUriString(redirect).build()
            .getQueryParams().getFirst("address");
        assertThat(URLDecoder.decode(carried, StandardCharsets.UTF_8)).isEqualTo(address);
        assertThat(redirect).contains("owner=");
    }

    /**
     * The default split always totals exactly 100.
     *
     * <p>It was {@code 100.0 / owners.size()} to two places, so three owners each got 33.33 and
     * every three-owner property a manager accepted the defaults on was created warned "total
     * 99.99" — the one screen whose job is to make the total come out right, suggesting a total that
     * does not. Asserted as the sum rather than as the literals, because 33.34 landing on the first
     * owner instead of the last would be equally correct and this test has no opinion about which.
     */
    @Test
    void thesuggestedSplitForThreeOwnersAddsUpToOneHundred() throws Exception {
        UUID a = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Trzecia", "anna.trzecia@example.com", null), true, LocalDate.now());
        UUID b = contacts.registerParty(workspaceId,
            new ContactDetails("Bartosz", "Trzeci", "bartosz.trzeci@example.com", null), true, LocalDate.now());
        UUID c = contacts.registerParty(workspaceId,
            new ContactDetails("Cecylia", "Trzecia", "cecylia.trzecia@example.com", null), true, LocalDate.now());

        String html = mvc.perform(get("/properties/new").param("address", "Trzecia 3")
                .param("owner", a.toString()).param("owner", b.toString()).param("owner", c.toString())
                .param("owners", "done"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        var shares = new java.util.ArrayList<java.math.BigDecimal>();
        var input = java.util.regex.Pattern
            .compile("<input[^>]*name=\"share\"[^>]*value=\"([^\"]*)\"").matcher(html);
        while (input.find()) {
            shares.add(new java.math.BigDecimal(input.group(1)));
        }

        assertThat(shares).hasSize(3);
        assertThat(shares.stream().reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
            .isEqualByComparingTo("100");

        // And the suggestion the screen offers is one the domain does not warn about.
        var post = post("/properties").with(csrf()).param("address", "Trzecia 3");
        List.of(a, b, c).forEach(id -> post.param("owner", id.toString()));
        shares.forEach(share -> post.param("share", share.toPlainString()));

        @SuppressWarnings("unchecked")
        var warnings = (List<String>) mvc.perform(post)
            .andExpect(status().is3xxRedirection()).andReturn().getFlashMap().get("warnings");
        assertThat(warnings).isEmpty();
    }

    /**
     * Removing the MIDDLE of three. Exercised nowhere before, and it is the operation most likely to
     * break the positional owner↔share pairing: the two that remain must stay in their own order and
     * must be the two that were not removed.
     *
     * <p>Driven as a POST-then-redirect, which is what the Usuń button does now. The redirect is
     * asserted to carry the two survivors and no token: removal happens once, in the POST, and the
     * URL the manager lands on can be reloaded without removing anybody a second time.
     */
    @Test
    void removingTheMiddleDraftedOwnerLeavesTheOtherTwoInOrder() throws Exception {
        UUID a = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Pierwsza", "anna.pierwsza@example.com", null), true, LocalDate.now());
        UUID b = contacts.registerParty(workspaceId,
            new ContactDetails("Bartosz", "Środkowy", "bartosz.srodkowy@example.com", null), true, LocalDate.now());
        UUID c = contacts.registerParty(workspaceId,
            new ContactDetails("Cecylia", "Ostatnia", "cecylia.ostatnia@example.com", null), true, LocalDate.now());

        String redirect = mvc.perform(post("/properties/new").with(csrf())
                .param("address", "Środkowa 2")
                .param("owner", a.toString()).param("owner", b.toString()).param("owner", c.toString())
                .param("remove", b.toString()))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).doesNotContain("_csrf").doesNotContain(b.toString());

        String html = followRedirect(redirect)
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Anna Pierwsza").contains("Cecylia Ostatnia")
            .doesNotContain("Bartosz Środkowy");
        // The hidden ids the next submit will carry, in order — a and c, and nothing of b.
        assertThat(html.indexOf(a.toString())).isLessThan(html.indexOf(c.toString()));
        assertThat(html).doesNotContain(b.toString());
        // And the address the manager typed is still in the box.
        assertThat(fieldsOf(formContaining(html, "Szukaj"))).containsEntry("address", "Środkowa 2");
    }

    /**
     * The sibling of {@code reachingTheSharesPhaseWithNobodyDraftedGoesBackAndSaysWhy}: owners, but
     * no address. Only one of the two branches was covered, and a branch nothing runs is a branch
     * nothing checks.
     */
    @Test
    void reachingTheSharesPhaseWithNoAddressGoesBackAndSaysWhy() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Bezadresna", "anna.bezadresna@example.com", null), true, LocalDate.now());

        mvc.perform(get("/properties/new").param("address", "   ")
                .param("owner", anna.toString()).param("owners", "done"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Najpierw podaj adres nieruchomości.")))
            // Back to phase one, picker and all — not a dead end.
            .andExpect(content().string(containsString("— albo nowy właściciel —")));
    }

    @Test
    void apropertyWithNoAddressIsRefused() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Bezadresowa", "anna.bezadresowa@example.com", null), true, LocalDate.now());

        mvc.perform(post("/properties").with(csrf())
                .param("address", "   ")
                .param("owner", anna.toString()).param("share", "100"))
            .andExpect(status().isBadRequest());
    }
}
