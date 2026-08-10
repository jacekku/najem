package pl.najem.app.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import pl.najem.acc.application.ArrearsBoardProjection;
import pl.najem.acc.application.InvoiceRepository;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.ContactService;
import pl.najem.contacts.application.InterestedParty;
import pl.najem.contacts.application.InterestService;
import pl.najem.contacts.application.NoSuchContactException;
import pl.najem.contacts.application.UnitInterestQuery;
import pl.najem.pm.application.TenancyBoardProjection;
import pl.najem.pm.application.TenancyDetailRow;
import pl.najem.pm.domain.LegalForm;
import pl.najem.reporting.application.UnitBoardQuery;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One unit, and the people who have asked about it.
 *
 * <p>The unit's facts come from Reporting and the people from Contacts, and the two cannot be
 * merged into one read: Reporting has never seen a name, because the PII lookaside keeps names out
 * of the events it projects from. That is not an inconvenience to route around — it is what makes
 * erasure complete, and a projection holding a name would outlive the deletion.
 *
 * <p>Renders what it is given. Whether a unit is let, and which of the three market states it is
 * in, are the projection's answers.
 */
@Controller
public class UnitScreenController {

    /**
     * `dd.mm.rrrr`, this codebase's stated date convention — same pattern as
     * {@link TimelineScreenController#DATE} and {@link ReserveScreenController#DATE}: no
     * {@code thymeleaf-extras-java8time} on this module's classpath, so a raw {@link LocalDate}
     * concatenated into a template's {@code th:text} would render its own ISO {@code toString()}
     * instead.
     */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** {@link InterestedParty}, with {@code desiredStart} pre-formatted for the template —
     *  {@code null} stays {@code null} (nobody has said when), never the string {@code "null"}. */
    public record InterestedPartyView(UUID interestId, String fullName, String email, String phone,
                                      BigDecimal willingToPay, String desiredStart) {
        static InterestedPartyView of(InterestedParty party) {
            return new InterestedPartyView(party.interestId(), party.fullName(), party.email(),
                party.phone(), party.willingToPay(),
                party.desiredStart() == null ? null : DATE.format(party.desiredStart()));
        }
    }

    /**
     * The five tabs, which double as the {@code active} value the {@code tabs} fragment compares
     * against each item's own label — the mechanism {@code ReportsScreenController} already uses,
     * and named constants for the same reason it gives: the template repeats the labels in its item
     * list, and a drift between the two renders a strip with nothing selected.
     *
     * <p>The order is the prototype's, and it is not arbitrary: Przegląd is what the screen opens
     * on, Konto is the one a manager chasing money goes to next.
     */
    static final String OVERVIEW = "Przegląd";
    static final String LEDGER = "Konto";
    static final String TENANT = "Najemca";
    static final String METERS = "Liczniki";
    static final String DOCUMENTS = "Dokumenty";

    /**
     * {@code ?tab=} to label, in the strip's own order.
     *
     * <p>An absent tab opens Przegląd, because that is what {@code /units/{id}} means; an
     * unrecognised one is a 404 rather than a silent fall back, the same call
     * {@code ReportsScreenController} makes — a typo that quietly renders the default is a link
     * somebody will keep sending round believing it points at the meters.
     *
     * <p><b>The keys are ASCII and the labels are not, deliberately.</b> The key is what appears in
     * a URL and what the template branches on; the label is what a manager reads. Branching on the
     * label would put {@code Przegląd} inside a {@code th:case}, where one missing diacritic is a
     * silently blank tab, and would make renaming a tab a change to every branch that mentions it.
     */
    static final String OVERVIEW_KEY = "przeglad";

    /** A strip entry: what the URL says and what the manager reads. */
    record Tab(String key, String label) {
    }

    private static final List<Tab> TABS = List.of(
        new Tab(OVERVIEW_KEY, OVERVIEW), new Tab("konto", LEDGER), new Tab("najemca", TENANT),
        new Tab("liczniki", METERS), new Tab("dokumenty", DOCUMENTS));

    /**
     * The Oś czasu card's four views, on {@code ?os=}. A second parameter rather than four more
     * tabs: these are all one question ("this unit over time") asked four ways, which is what the
     * prototype draws as chips inside the card rather than as siblings of Przegląd.
     */
    static final String SPELLS = "Najmy";
    static final String PAYMENTS = "Płatności";
    static final String RENT = "Czynsz";
    static final String MAINTENANCE = "Konserwacja";

    static final String SPELLS_KEY = "najmy";

    private static final List<Tab> TIMELINES = List.of(
        new Tab(SPELLS_KEY, SPELLS), new Tab("platnosci", PAYMENTS), new Tab("czynsz", RENT),
        new Tab("konserwacja", MAINTENANCE));

    /**
     * One person on the tenancy, named. Initials for the avatar; the two contact lines beneath.
     *
     * <p>Contacts is the only module that has ever seen a name — Reporting projects from events the
     * PII lookaside keeps names out of, which is what makes erasure complete. So this record is
     * assembled here, in the composition root, and no module learns about another.
     */
    public record Person(String initials, String name, String email, String phone) {
    }

    /**
     * The Bieżąca umowa card, and the Najemca tab, from the one tenancy running in this unit today.
     *
     * <p><b>Every field here is real.</b> The term, the legal form, the components, the deposit,
     * the payment day and the parties come from PM; the names from Contacts; the balance and its
     * colour from Accounting. Nothing on this record is invented — {@link UnitDetailFake} covers
     * the widgets that have no read side, and mixing the two on one card is how a reviewer stops
     * trusting either.
     *
     * @param balance NEGATIVE when the tenant owes, the sign the register already renders and the
     *                sign a ledger carries. Accounting reports outstanding as a positive figure, so
     *                it is negated once, here.
     * @param colour  accounting's, verbatim, for {@code arrearsPill}. Null when the arrears board
     *                has no row for this tenancy — it has not spoken, which is not the same as
     *                green.
     * @param rent    null unless the contract declares a component split, which {@code split} says
     *                outright. A zero here would claim a zero-złoty rent was agreed.
     */
    public record Contract(UUID tenancyId, String term, String legalForm, String rentDay,
                           boolean split, BigDecimal rent, BigDecimal adminFee,
                           BigDecimal mediaAdvance, BigDecimal monthlyTotal, BigDecimal deposit,
                           BigDecimal balance, ArrearsColour colour, String paymentReference,
                           List<Person> tenants, List<Person> guarantors) {
    }

    private final UnitBoardQuery units;
    private final UnitInterestQuery interested;
    private final Clock clock;
    private final ContactService contacts;
    private final InterestService interests;
    private final ContactDirectory directory;
    private final TenancyBoardProjection tenancies;
    private final InvoiceRepository invoices;
    private final ArrearsBoardProjection arrears;

    public UnitScreenController(UnitBoardQuery units, UnitInterestQuery interested, Clock clock,
                                ContactService contacts, InterestService interests,
                                ContactDirectory directory, TenancyBoardProjection tenancies,
                                InvoiceRepository invoices, ArrearsBoardProjection arrears) {
        this.units = units;
        this.interested = interested;
        this.clock = clock;
        this.contacts = contacts;
        this.interests = interests;
        this.directory = directory;
        this.tenancies = tenancies;
        this.invoices = invoices;
        this.arrears = arrears;
    }

    /**
     * Whether the manager picked somebody we already know.
     *
     * <p>Package-private and static so it can be tested without booting anything — the
     * {@code SearchGroupingTest} precedent. Blank is "no", and a malformed value is refused rather
     * than falling through to the create path: a garbled hidden field silently registering a second
     * person is the duplicate this screen exists to prevent. Refused as an
     * {@link InvalidContactIdException} — a 400, not a 500 — rather than the bare
     * {@link IllegalArgumentException} {@link UUID#fromString} throws, so the failure carries a type
     * {@link WebErrorAdvice} can map without also catching every unrelated bad-argument bug in scope.
     */
    static Optional<UUID> chosen(String contactId) {
        if (contactId == null || contactId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(contactId.strip()));
        } catch (IllegalArgumentException e) {
            throw new InvalidContactIdException(contactId, e);
        }
    }

    @GetMapping("/units/{unitId}")
    public String unit(@PathVariable UUID unitId,
                       @RequestParam(name = "tab", required = false) String tab,
                       @RequestParam(name = "os", required = false) String os,
                       @RequestParam(name = "q", required = false) String term,
                       @RequestParam(name = "contactId", required = false) String contactId,
                       WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        // Unknown and foreign are the same 404, as they are everywhere else here: an id that
        // answers differently for a unit in another agency tells a caller it exists.
        var unit = units.forUnit(workspaceId, unitId, LocalDate.now(clock))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("unit", unit);
        // The key is what the template branches on; the label is what the strip shows and what the
        // tabs fragment matches `active` against. Both come from one list, so a renamed tab cannot
        // leave a strip selecting nothing.
        String tabKey = key(TABS, tab, OVERVIEW_KEY);
        String timelineKey = key(TIMELINES, os, SPELLS_KEY);
        model.addAttribute("tab", tabKey);
        model.addAttribute("activeTab", labelOf(TABS, tabKey));
        model.addAttribute("tabs", strip(TABS, unitId, null));
        model.addAttribute("timeline", timelineKey);
        model.addAttribute("activeTimeline", labelOf(TIMELINES, timelineKey));
        // The Oś czasu chips live on Przegląd, so each one keeps the tab it is standing on. Without
        // that, clicking a chip would silently throw the reader back to the default tab.
        model.addAttribute("timelines", strip(TIMELINES, unitId, tabKey));
        model.addAttribute("contract", contract(workspaceId, unit.currentTenancyId()));
        model.addAttribute("fake", UnitDetailFake.VIEW);
        model.addAttribute("interested", interested.activeForUnit(workspaceId, unitId).stream()
            .map(InterestedPartyView::of).toList());

        // "Type something" and "nobody matched" are different answers and must not share a message,
        // for the same reason the search screen distinguishes them: a blank term returns no rows
        // exactly as a term that matched nothing does, so the difference cannot come from the result.
        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        model.addAttribute("hits", directory.search(workspaceId, term));

        // A well-formed contactId this workspace does not know is refused the same way a malformed
        // one is refused by chosen() itself: not silently downgraded to the create-a-new-person
        // path. It is a not-found, not bad input, so it is the same NoSuchContactException the POST
        // path already throws from ContactDirectory.requireIn — one concept, one type, one 404.
        var picked = chosen(contactId);
        model.addAttribute("chosenId", picked.orElse(null));
        model.addAttribute("chosen", picked
            .map(id -> directory.find(workspaceId, id).orElseThrow(() -> new NoSuchContactException(id)))
            .orElse(null));
        return "unit";
    }

    /**
     * A {@code ?tab=} / {@code ?os=} value as the label the fragment selects on.
     *
     * <p>Absent is the default view; anything else must be a key, and an unrecognised one is a 404.
     * Falling back to the default instead would answer 200 for {@code ?tab=likcznik}, which reads
     * as "the meters tab is empty" — a wrong answer that looks like a right one, and one somebody
     * would keep sending round. The same call {@code ReportsScreenController} makes for its own
     * strip, and made the same way so the two screens cannot diverge on it.
     */
    private static String key(List<Tab> known, String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return known.stream()
            .map(Tab::key)
            .filter(value::equals)
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Always found: {@link #key} has already refused anything that is not one of these. */
    private static String labelOf(List<Tab> known, String key) {
        return known.stream()
            .filter(entry -> entry.key().equals(key))
            .map(Tab::label)
            .findFirst().orElseThrow();
    }

    /**
     * A strip as the {@code tabs} fragment reads it — a list of {@code {label, href}}.
     *
     * <p>Built here rather than written out in the template, which is where
     * {@code ReportsScreenController} left it and where its own javadoc admits the labels are then
     * repeated and can drift. One list of tabs, one place, and the href is derived from the key so
     * a link and the branch it selects cannot disagree.
     *
     * <p>{@code keep} is the OTHER strip's current key, carried along so a chip does not reset the
     * tab it sits on; null for the tab strip itself, which resets the timeline chip to its default
     * on purpose — a manager switching to Konto and back expects Przegląd as they left it, not a
     * URL still carrying a chip they cannot see.
     */
    private static List<Map<String, String>> strip(List<Tab> tabs, UUID unitId, String keep) {
        String base = "/units/" + unitId;
        return tabs.stream()
            .map(tab -> Map.of("label", tab.label(), "href", keep == null
                ? base + "?tab=" + tab.key()
                : base + "?tab=" + keep + "&os=" + tab.key()))
            .toList();
    }

    /**
     * The contract running in this unit today, from three modules, or null when the unit is empty.
     *
     * <p>Null rather than an empty {@link Contract}: a vacant unit has no term, no deposit and no
     * balance, and a record full of nulls would have every consumer re-deciding what an absent term
     * means. The template asks once, at the top, and renders the vacancy state instead.
     *
     * <p><b>A tenancy id the register will not confirm is treated as no tenancy at all.</b>
     * {@code UnitBoardQuery} reads reporting_unit_period and PM reads pm_tenancy; they are two
     * projections of one stream and can be a beat apart, and PM's is the one that owns the
     * contract. Rendering a card from a half-answer is worse than rendering the vacancy — see the
     * WorkspaceGuard note in architecture.md, which is the same failure: one question, two answers,
     * and the derived one trusted.
     */
    private Contract contract(UUID workspaceId, UUID tenancyId) {
        if (tenancyId == null) {
            return null;
        }
        var tenancy = tenancies.forTenancy(workspaceId, tenancyId).orElse(null);
        if (tenancy == null) {
            return null;
        }
        // Absent from the map is "owes nothing", which InvoiceRepository.outstandingByTenancy
        // states rather than leaves to be inferred — so ZERO is a reading of that answer, not a
        // default. Both reads are workspace-wide and asked once; neither is per-row.
        var owed = invoices.outstandingByTenancy(workspaceId)
            .getOrDefault(tenancyId, BigDecimal.ZERO);
        var colour = arrears.forWorkspace(workspaceId).stream()
            .filter(row -> row.tenancyId().equals(tenancyId))
            .map(ArrearsBoardProjection.Row::colour)
            .findFirst().orElse(null);
        return new Contract(tenancyId, term(tenancy), legalForm(tenancy.legalForm()),
            tenancy.rentDay() + ". dnia miesiąca", tenancy.componentSplit(), tenancy.rent(),
            tenancy.adminFee(), tenancy.mediaAdvance(), tenancy.monthlyTotal(),
            tenancy.depositAmount(), owed.negate(), colour, tenancy.paymentReference(),
            people(workspaceId, tenancy.tenantContactIds()),
            people(workspaceId, tenancy.guarantorContactIds()));
    }

    /**
     * {@code 01.09.2025 – ∞} for an indefinite tenancy — the register's own rendering, and the same
     * argument: a null end date means the unit does not free up at all, so an em dash would read as
     * "unknown" where the infinity sign says what is true.
     */
    private static String term(TenancyDetailRow tenancy) {
        return DATE.format(tenancy.startDate()) + " – "
            + (tenancy.endDate() == null ? "∞" : DATE.format(tenancy.endDate()));
    }

    /**
     * The legal form as a manager says it, not as the enum spells it.
     *
     * <p>A switch rather than a lookup keyed on the enum, so adding a fourth form is a compile
     * error here instead of a screen rendering the constant's own SHOUTING name to a landlord.
     */
    private static String legalForm(LegalForm form) {
        return switch (form) {
            case ZWYKLY -> "Najem zwykły";
            case OKAZJONALNY -> "Najem okazjonalny";
            case INSTYTUCJONALNY -> "Najem instytucjonalny";
        };
    }

    /**
     * Contact ids as named people, in the order the projection gave them.
     *
     * <p>A contact the directory does not know is dropped rather than rendered as a blank card: the
     * read is already workspace-scoped and answers empty for unknown and foreign alike, and a
     * placeholder person would be this screen inventing one. A tenancy whose parties predate
     * pm_tenancy_party has none at all, which the template renders as an unnamed tenancy.
     */
    private List<Person> people(UUID workspaceId, List<UUID> contactIds) {
        return contactIds.stream()
            .map(id -> directory.find(workspaceId, id).orElse(null))
            .filter(java.util.Objects::nonNull)
            .map(contact -> new Person(
                letter(contact.givenName()) + letter(contact.surname()),
                (contact.givenName() + " " + contact.surname()).trim(),
                contact.email(), contact.phone()))
            .toList();
    }

    /** Blank rather than an exception for an absent or empty name half — a contact may have one. */
    private static String letter(String name) {
        return name == null || name.isBlank() ? "" : name.substring(0, 1).toUpperCase();
    }

    /**
     * Somebody phoned about this unit.
     *
     * <p>One endpoint and two paths, because from the manager's side it is one action. Which path
     * ran is the presence of {@code contactId} — the hidden field the "Wybierz" link fills in.
     *
     * <p>The person is registered before the interest, and both are separate transactions on the
     * services that own them. A failure between the two leaves a person with no interest, which a
     * manager can see and fix; the reverse would leave an interest pointing at nobody, which the
     * inner join would hide.
     */
    @PostMapping("/units/{unitId}/interests")
    public String addInterest(@PathVariable UUID unitId,
                              @RequestParam(required = false) String contactId,
                              @RequestParam(required = false) String givenName,
                              @RequestParam(required = false) String surname,
                              @RequestParam(required = false) String email,
                              @RequestParam(required = false) String phone,
                              // An unticked checkbox submits NOTHING, so this parameter is absent
                              // rather than "false" — and a primitive boolean with required=false
                              // and no default fails to bind a missing value. defaultValue is what
                              // makes "the manager did not tick it" the ordinary path.
                              @RequestParam(defaultValue = "false") boolean infoClauseServed,
                              @RequestParam(required = false) BigDecimal willingToPay,
                              @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desiredStart,
                              WebWorkspace workspace) {
        UUID workspaceId = workspace.workspaceId();
        UUID person = chosen(contactId).orElseGet(() -> contacts.registerLead(workspaceId,
            new ContactDetails(givenName, surname, email, phone),
            infoClauseServed, LocalDate.now(clock)));
        interests.register(workspaceId, person, unitId, willingToPay, desiredStart);
        return "redirect:/units/" + unitId + "?tab=najemca";
    }

    /**
     * POST rather than DELETE because an HTML form cannot issue one, and the same shape the bank
     * screen's two buttons already use.
     *
     * <p>The service's own lookup is the workspace gate — it names the workspace, so a foreign or
     * unknown interest finds nothing and the command is refused before anything is appended. This
     * passes the workspace on rather than checking it here.
     */
    @PostMapping("/units/{unitId}/interests/{interestId}/withdraw")
    public String withdraw(@PathVariable UUID unitId, @PathVariable UUID interestId,
                           WebWorkspace workspace) {
        interests.withdraw(workspace.workspaceId(), interestId, LocalDate.now(clock));
        return "redirect:/units/" + unitId + "?tab=najemca";
    }
}
