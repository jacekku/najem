package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import pl.najem.app.SharedDatabase;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.ContactService;
import pl.najem.contacts.application.UnitInterestQuery;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;
import pl.najem.reporting.application.ProjectionRunner;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scenario the screen exists for, through the stack a manager actually uses.
 *
 * <p>Container tier, because the three things this covers are all things only a running stack
 * decides: the join between the interest and the person, the redirect, and the CSRF filter.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + UnitScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Tag("integration")
class UnitScreenTest extends SharedDatabase {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000010";

    /**
     * Independently declared, the way ReserveScreenTest's own DATE constant is — not calling the
     * production formatter and not pasting its output, so a revert of
     * UnitScreenController.InterestedPartyView.desiredStart to raw ISO yyyy-MM-dd (the module has
     * no thymeleaf-extras-java8time, so this formatting can only happen in Java) fails this
     * assertion instead of passing it vacuously.
     */
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired PortfolioService portfolio;
    @Autowired ProjectionRunner projections;
    @Autowired ContactDirectory directory;
    @Autowired ContactService contactService;
    @Autowired UnitInterestQuery interested;
    @Autowired JdbcTemplate jdbc;

    /** Static: JUnit builds a new instance per method, so an instance guard would never be false. */
    static UUID agency;
    static UUID unitId;

    @BeforeEach
    void aUnitToRingAbout() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency == null) {
            agency = workspaces.create("Agencja Zainteresowanych", operator, LocalDate.now());
            var property = portfolio.createProperty(agency, "ul. Testowa 1, 00-001 Warszawa",
                List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
            unitId = portfolio.addUnit(agency, property, "m. 2", new BigDecimal("2850"));
        }
        // Drained explicitly rather than waited for. The scheduled poll would make this pass or
        // fail on timing, which is the one kind of red nobody can reproduce.
        projections.runOnce();
    }

    private void ring(String... params) throws Exception {
        var request = post("/units/" + unitId + "/interests").with(csrf());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        mvc.perform(request)
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/units/" + unitId + "?tab=najmy"));
    }

    @Test
    void aleadPhonesAboutAUnitAndAppearsOnItsScreen() throws Exception {
        ring("givenName", "Piotr", "surname", "Nowak",
            "email", "p.nowak@example.com", "phone", "+48500000000",
            "infoClauseServed", "true",
            "willingToPay", "2900.00", "desiredStart", "2026-09-01");

        mvc.perform(get("/units/" + unitId).param("tab", "najmy"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Piotr Nowak")))
            // The stated format, not the server locale's — see units.html.
            .andExpect(content().string(containsString("2 900,00")))
            // desiredStart is pre-formatted dd.MM.yyyy in InterestedPartyView (Java, not
            // Thymeleaf — see this class's DATE constant); rendered as text in unit.html's table,
            // not fed to an <input type="date">, so this is the format that must appear, not ISO.
            .andExpect(content().string(containsString(DATE.format(LocalDate.of(2026, 9, 1)))));
    }

    /** The basis is the module's decision, and this is where it becomes visible in a row. */
    @Test
    void aleadIsStoredUnderLegitimateInterestWithTheClauseTheManagerConfirmed() throws Exception {
        ring("givenName", "Marta", "surname", "Wisniewska",
            "email", "m.w@example.com", "infoClauseServed", "true");

        var row = jdbc.queryForMap(
            "select lawful_basis, info_clause_served_at from contacts_person where email = ?",
            "m.w@example.com");

        assertThat(row.get("lawful_basis")).isEqualTo("legitimate-interest");
        assertThat(row.get("info_clause_served_at")).isNotNull();
    }

    /** Unticked means unticked. Recording today anyway would be the application asserting it. */
    @Test
    void anunconfirmedClauseIsStoredAsAbsent() throws Exception {
        ring("givenName", "Jan", "surname", "Bezklauzuli", "email", "j.b@example.com");

        assertThat(jdbc.queryForObject(
            "select info_clause_served_at from contacts_person where email = ?",
            LocalDate.class, "j.b@example.com")).isNull();
    }

    /**
     * The point of searching before creating. A second interest under the picked contact must not
     * produce a second person: erasure deletes one row, and would leave the other behind.
     */
    @Test
    void pickingSomebodyWeKnowDoesNotCreateThemAgain() throws Exception {
        ring("givenName", "Tomasz", "surname", "Zielinski", "email", "t.z@example.com");
        var known = directory.search(agency, "Zielinski").getFirst().contactId();

        ring("contactId", known.toString(), "willingToPay", "3100.00");

        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_person where workspace_id = ? and email = ?",
            Integer.class, agency, "t.z@example.com")).isEqualTo(1);
        assertThat(interested.activeForUnit(agency, unitId))
            .filteredOn(party -> party.contactId().equals(known))
            .hasSize(2);
    }

    @Test
    void withdrawingTakesThemOffTheList() throws Exception {
        ring("givenName", "Krzysztof", "surname", "Wycofany", "email", "k.w@example.com");
        var interestId = interested.activeForUnit(agency, unitId).stream()
            .filter(party -> "Wycofany".equals(party.surname()))
            .findFirst().orElseThrow().interestId();

        mvc.perform(post("/units/" + unitId + "/interests/" + interestId + "/withdraw").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/units/" + unitId + "?tab=najmy"));

        mvc.perform(get("/units/" + unitId).param("tab", "najmy"))
            .andExpect(content().string(not(containsString("Krzysztof Wycofany"))));
    }

    // ----------------------------------------------------------------------------------------
    // The tab strip
    // ----------------------------------------------------------------------------------------

    /**
     * Each of the five tabs renders ITS OWN content and not another's.
     *
     * <p>One test rather than five, and asserting both halves each time — what this tab shows and
     * what it must not — because five one-sided tests all pass against a screen that renders every
     * tab at once, which is precisely what a dropped {@code th:if} produces. The negative is the
     * assertion; the positive only proves the page loaded.
     *
     * <p><b>Never assert a negative on a tab's LABEL.</b> The strip renders all five labels on every
     * tab, by design — so {@code doesNotContain("Liczniki")} fails on a perfectly correct page. Each
     * string below is content from inside one tab's body and appears nowhere else: a card title, or
     * a row only that tab's data carries. This is written down because the first version of this
     * test made exactly that mistake and cost an 8-minute run to find.
     */
    @Test
    void eachTabRendersItsOwnContentAndNotAnothers() throws Exception {
        assertThat(tab(null)).contains("Oś czasu").doesNotContain("Konto najemcy", "Woda zimna");
        assertThat(tab("konto")).contains("Konto najemcy").doesNotContain("Oś czasu", "Zainteresowani");
        assertThat(tab("najmy")).contains("Zainteresowani").doesNotContain("Konto najemcy", "Oś czasu");
        assertThat(tab("liczniki")).contains("Woda zimna").doesNotContain("Konto najemcy", "Oś czasu");
        // "Świadectwo energetyczne", not "Protokół zdawczo-odbiorczy". Prototype v2 splits the unit's
        // documents from the CONTRACT's, and the protokół moved with the contract — it names parties
        // and expires with the tenancy, where a świadectwo survives every tenant. So the string this
        // asserts on had to change with the split, and it is deliberately one that now appears on
        // this tab and nowhere else.
        assertThat(tab("dokumenty")).contains("Świadectwo energetyczne")
            .doesNotContain("Konto najemcy", "Woda zimna");
    }

    /**
     * An unrecognised tab is a 404, not a silent fall back to Przegląd.
     *
     * <p>A typo that renders the default answers 200 for a URL that names nothing — which reads as
     * "the meters tab is empty" rather than "there is no such tab", and is a link somebody keeps
     * sending round. Both parameters are checked, because they are validated by one method and a
     * regression would take both with it.
     */
    @Test
    void anunrecognisedTabOrTimelineIsNotFound() throws Exception {
        mvc.perform(get("/units/" + unitId).param("tab", "likcznik"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/units/" + unitId).param("os", "platnosc"))
            .andExpect(status().isNotFound());
    }

    /**
     * The Oś czasu chips carry the tab they are standing on.
     *
     * <p>Without it, every chip would point at {@code ?os=…} alone, the tab would fall back to its
     * default, and clicking a chip on Przegląd would work by accident — right until the card is
     * reused anywhere else. The strip is built once in the controller precisely so this holds.
     */
    @Test
    void atimelineChipKeepsTheTabItSitsOn() throws Exception {
        assertThat(tab(null)).contains("/units/" + unitId + "?tab=przeglad&amp;os=platnosci");
    }

    /** The four timeline views are one card, and only one of them renders at a time. */
    @Test
    void eachTimelineViewRendersAloneOnTheOverviewTab() throws Exception {
        var spells = mvc.perform(get("/units/" + unitId)).andReturn()
            .getResponse().getContentAsString();
        assertThat(spells).contains("Śr. długość najmu").doesNotContain("W terminie");

        var payments = mvc.perform(get("/units/" + unitId).param("os", "platnosci")).andReturn()
            .getResponse().getContentAsString();
        assertThat(payments).contains("W terminie").doesNotContain("Śr. długość najmu");
    }

    /**
     * A vacant unit says so instead of rendering an empty contract card, and every tab still loads.
     *
     * <p>The unit this class builds is never let, so this is the state all the tab assertions above
     * are made in — worth naming, because a NullPointerException on a null contract would show up as
     * a 500 in exactly one of them and be read as a template typo.
     */
    @Test
    void avacantUnitRendersTheVacancyStateRatherThanABlankContract() throws Exception {
        assertThat(tab(null)).contains("Pustostan");
        assertThat(tab("najmy")).contains("Pustostan — nikt nie wynajmuje tego lokalu.");
    }

    /**
     * The renamed tab leads with the unit's own tenancy history, and that list is REAL.
     *
     * <p>This unit has never been let, so the list is empty and says so. The empty state is the
     * assertion worth having: it is the difference between "this unit has had no tenancies" and "the
     * register read returned nothing because it broke", which look identical without it — and it is
     * the state every other assertion in this class is made in.
     */
    @Test
    void thenajmyTabLeadsWithTheUnitsOwnTenancyHistory() throws Exception {
        var html = tab("najmy");

        assertThat(html).contains("Najmy tego lokalu");
        assertThat(html).contains("Ten lokal nie miał jeszcze żadnego najmu.");
        assertThat(html).as("counted, and declined — 0 takes the many form").contains("0 najmów");
    }

    /**
     * The meters tab is three things now, not one table.
     *
     * <p>Asserting one string from each — the rail, the per-meter card, the history — because the
     * failure this catches is a whole section silently not rendering, which a single assertion on
     * "Woda zimna" (present in two of the three) would miss.
     */
    @Test
    void themetersTabCarriesTheDeadlinesTheCardsAndTheHistory() throws Exception {
        var html = tab("liczniki");

        assertThat(html).as("the rail of what is due").contains("Najbliższe terminy");
        assertThat(html).as("a card per meter, with its legalisation date")
            .contains("Legalizacja do").contains("EL-88214773");
        assertThat(html).as("the history, with the source of each reading")
            .contains("Historia odczytów").contains("zdjęcie najemcy");
    }

    /**
     * The documents tab holds the UNIT's own papers.
     *
     * <p>The split is the change worth pinning: a protokół zdawczo-odbiorczy names parties and
     * expires with the tenancy, so it belongs to the contract, while a świadectwo energetyczne
     * survives every tenant.
     *
     * <p><b>The signpost to the contract's documents is asserted ABSENT here, and that is the
     * point.</b> This class's unit is vacant, so there is no contract to point at and the bar is
     * guarded on {@code contract != null} — pointing a manager at an umowa that does not exist
     * would be worse than saying nothing. An earlier version of this test asserted the signpost's
     * TEXT was present, which passed only because that sentence was also duplicated into the
     * card's footer; deduplicating the copy is what exposed it.
     */
    @Test
    void thedocumentsTabHoldsTheUnitsOwnPapers() throws Exception {
        var html = tab("dokumenty");

        assertThat(html).contains("Dokumenty lokalu");
        assertThat(html).contains("Świadectwo energetyczne").contains("Przegląd kominiarski");
        assertThat(html).as("deadlines are called out above the list, not buried in it")
            .contains("Terminy");
        assertThat(html).as("a vacant unit points at no contract documents, because it has none")
            .doesNotContain("Otwórz umowę");
    }

    /** The two unbuilt header actions are drawn and are not links — this app never fakes a route. */
    @Test
    void adrawnButUnbuiltActionIsNotALink() throws Exception {
        var html = tab(null);

        assertThat(html).contains("Wyślij ponaglenie").contains("btn--unbuilt");
        assertThat(html).as("no anchor was invented for it")
            .doesNotContain("<a class=\"btn btn--unbuilt\"");
    }

    private String tab(String tab) throws Exception {
        var request = get("/units/" + unitId);
        if (tab != null) {
            request = request.param("tab", tab);
        }
        return mvc.perform(request).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    /**
     * A unit belonging to another agency must 404 rather than render an empty screen. An empty
     * screen says "nobody is interested", which is a different claim from "not yours" — and the
     * interest list alone would render empty quite happily.
     */
    @Test
    void aunitInAnotherAgencyIsNotFound() throws Exception {
        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        UUID theirs = workspaces.create("Cudza agencja", stranger, LocalDate.now());
        var theirProperty = portfolio.createProperty(theirs, "ul. Cudza 9, Sopot",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        var theirUnit = portfolio.addUnit(theirs, theirProperty, "m. 2", new BigDecimal("2000"));
        projections.runOnce();

        mvc.perform(get("/units/" + theirUnit)).andExpect(status().isNotFound());
    }

    /**
     * A unit id nobody ever created must 404 the same way a foreign one does. If unknown and
     * foreign answered differently, a caller could distinguish "not yours" from "does not exist" —
     * exactly the disclosure the undifferentiated 404 exists to prevent.
     */
    @Test
    void aunitThatWasNeverCreatedIsNotFound() throws Exception {
        mvc.perform(get("/units/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    /** Without this, the refusal above could be CSRF, or a route that does not exist. */
    @Test
    void thewriteIsRefusedWithoutACsrfToken() throws Exception {
        mvc.perform(post("/units/" + unitId + "/interests")
                .param("givenName", "Nikt").param("surname", "Nigdy"))
            .andExpect(status().isForbidden());
    }

    /**
     * A stale or foreign interest id must 404, not render a 500 page — the case
     * {@code InterestService.withdraw} already refuses via {@code NoSuchInterestException}, which
     * previously had no mapping for this screen.
     *
     * <p>Not literally "withdraw the same interest twice": a second withdraw of an already-withdrawn
     * interest this workspace still owns now throws {@code InterestNotActiveException}, not
     * {@code NoSuchInterestException} — see {@code InterestRepository.find} and
     * {@code InterestService.requireActive}, which is a distinct error-mapping concern outside this
     * fix wave. An id nobody ever registered exercises the {@code NoSuchInterestException} path a
     * double-submit would hit if the row had truly gone away.
     */
    @Test
    void withdrawingAnInterestThatWasNeverRegisteredIsNotFound() throws Exception {
        mvc.perform(post("/units/" + unitId + "/interests/" + UUID.randomUUID() + "/withdraw")
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    /**
     * A {@code contactId} that is a valid UUID but belongs to another workspace must 404, not render
     * a 500 page. Registered directly through {@link ContactService} rather than through the screen,
     * because the point of this test is the foreign id reaching {@code InterestService.register} —
     * how the contact came to exist in the other workspace is incidental.
     */
    @Test
    void postingAnInterestWithAContactIdFromAnotherWorkspaceIsNotFound() throws Exception {
        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        UUID theirs = workspaces.create("Cudza agencja (kontakt)", stranger, LocalDate.now());
        UUID theirContact = contactService.registerLead(theirs,
            new ContactDetails("Obcy", "Kontakt", "obcy.kontakt@example.com", null),
            true, LocalDate.now());

        mvc.perform(post("/units/" + unitId + "/interests").with(csrf())
                .param("contactId", theirContact.toString())
                .param("willingToPay", "1000.00"))
            .andExpect(status().isNotFound());
    }

    /** The malformed-id claim {@code LeadFormTest} makes about {@code chosen} is true end to end. */
    @Test
    void amalformedContactIdOnTheGetIsABadRequest() throws Exception {
        mvc.perform(get("/units/" + unitId).param("contactId", "not-a-uuid"))
            .andExpect(status().isBadRequest());
    }

    /**
     * A well-formed {@code contactId} this workspace does not know must be refused the same way a
     * malformed one is — not silently downgraded to the create-a-new-person form. It is a not-found,
     * not bad input, so it is a 404 rather than the 400 the previous test gets.
     */
    @Test
    void awellFormedButForeignContactIdOnTheGetIsNotFound() throws Exception {
        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        UUID theirs = workspaces.create("Cudza agencja (widok)", stranger, LocalDate.now());
        UUID theirContact = contactService.registerLead(theirs,
            new ContactDetails("Obcy", "Widok", "obcy.widok@example.com", null),
            true, LocalDate.now());

        mvc.perform(get("/units/" + unitId).param("contactId", theirContact.toString()))
            .andExpect(status().isNotFound());
    }
}
