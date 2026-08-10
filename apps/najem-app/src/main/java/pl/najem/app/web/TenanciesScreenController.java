package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import pl.najem.acc.application.ArrearsBoardProjection;
import pl.najem.acc.application.InvoiceRepository;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.pm.application.TenancyBoardProjection;
import pl.najem.pm.application.TenancyBoardRow;
import pl.najem.pm.domain.Tenancy;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The register: every tenancy the agency has let, with who is on it, what it costs and what it owes.
 *
 * <p><b>Three modules meet here and none of them learns about another.</b> Property management says
 * which tenancies exist and which unit each occupies, contacts turns a contact id into a person,
 * accounting says what is owed and what colour that is. Composing their read sides is the
 * composition root's job and no module's — the same division {@link TenancyLabels} works under, and
 * the reason the UI lives in this application rather than in any module.
 *
 * <p><b>Four queries for the page, not four per row.</b> The register, the balances, the arrears
 * board and the contacts are each fetched whole and joined in memory. Every one of them would
 * otherwise be an N+1 that multiplies with the agency's size, which is precisely where a portfolio
 * screen goes wrong — {@code UnitBoardQuery}'s javadoc makes the argument and it applies at every
 * level. Contacts is the one exception and it is a bounded fan-out: {@link ContactDirectory}
 * offers no bulk read, so this asks per distinct contact, once, deduplicated across rows.
 *
 * <p><b>Nothing about arrears is decided here.</b> The colour arrives from accounting and is handed
 * to the {@code arrearsPill} fragment verbatim, exactly as {@code ReportsScreenController} does —
 * a screen that recomputed it would be a second opinion on a legal state, and rule A7 exists to
 * stop the derived board from becoming one.
 */
@Controller
public class TenanciesScreenController {

    /** `dd.mm.rrrr`, this app's stated convention — see {@code TimelineScreenController}'s note on
     *  why the format is stated here rather than inherited from the server locale. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * One row as the template reads it.
     *
     * @param initials  for the avatar. Empty when nobody is named, which the fragment renders as an
     *                  empty circle rather than as a guess.
     * @param tenant    every tenant on the tenancy, comma-separated. A tenancy legally may have
     *                  several and the register is where that shows; naming only the first would
     *                  quietly make a co-tenant disappear from the only screen that lists them.
     * @param balance   NEGATIVE when the tenant owes — the sign the design carries and the sign a
     *                  ledger carries. Accounting reports what is outstanding as a positive figure,
     *                  so this is where it is negated, once.
     * @param colour    accounting's, verbatim, for {@code arrearsPill}. Null when the board has no
     *                  row for this tenancy at all.
     */
    public record Row(UUID tenancyId, String initials, String tenant, String where, String term,
                      BigDecimal rent, BigDecimal balance, ArrearsColour colour) {
    }

    private final TenancyBoardProjection tenancies;
    private final InvoiceRepository invoices;
    private final ArrearsBoardProjection board;
    private final ContactDirectory contacts;

    public TenanciesScreenController(TenancyBoardProjection tenancies, InvoiceRepository invoices,
                                     ArrearsBoardProjection board, ContactDirectory contacts) {
        this.tenancies = tenancies;
        this.invoices = invoices;
        this.board = board;
        this.contacts = contacts;
    }

    @GetMapping("/tenancies")
    public String tenancies(WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        var register = tenancies.forWorkspace(workspaceId);
        var outstanding = invoices.outstandingByTenancy(workspaceId);
        var colours = board.forWorkspace(workspaceId).stream()
            .collect(Collectors.toMap(ArrearsBoardProjection.Row::tenancyId,
                ArrearsBoardProjection.Row::colour));
        var names = names(workspaceId, register);

        model.addAttribute("rows", register.stream()
            .map(row -> row(row, names, outstanding, colours))
            .toList());
        model.addAttribute("summary", summary(register));
        return "tenancies";
    }

    private static Row row(TenancyBoardRow row, Map<UUID, ContactDetails> names,
                           Map<UUID, BigDecimal> outstanding, Map<UUID, ArrearsColour> colours) {
        var tenants = row.tenantContactIds().stream()
            .map(names::get)
            .filter(java.util.Objects::nonNull)
            .toList();
        var named = tenants.stream()
            .map(contact -> (contact.givenName() + " " + contact.surname()).trim())
            .filter(name -> !name.isBlank())
            .toList();
        // Absent from the balances map is "owes nothing", which the port's javadoc states rather
        // than leaves to be inferred — so ZERO here is a reading of that answer, not a default.
        var owed = outstanding.getOrDefault(row.tenancyId(), BigDecimal.ZERO);
        return new Row(row.tenancyId(), initials(tenants), String.join(", ", named),
            row.propertyAddress() + " · " + row.unitName(), term(row), row.monthlyTotal(),
            owed.negate(), colours.get(row.tenancyId()));
    }

    /**
     * {@code 01.09.2025 – ∞} for an indefinite tenancy.
     *
     * <p>A null end date is not a missing value — it means the unit does not free up at all, which
     * {@link TenancyBoardRow} says explicitly so that this is not the place it gets guessed. An em
     * dash would read as "unknown"; the infinity sign says what is actually true, and it is what
     * the arrears and occupancy code already means by an open period.
     */
    private static String term(TenancyBoardRow row) {
        return DATE.format(row.startDate()) + " – "
            + (row.endDate() == null ? "∞" : DATE.format(row.endDate()));
    }

    /**
     * The first tenant's own two initials — {@code AK} for Anna Kowalska, as the mockup draws it.
     *
     * <p>The first tenant's, not one letter from each of several: the row has ONE circle, and the
     * mockup fills it with a person rather than with a roster. A co-tenant is named in full in the
     * text beside it, which is where a second name belongs. (An earlier version took a letter per
     * tenant and rendered a lone "A" for Anna Kowalska — right by its own rule, wrong against the
     * drawing, and caught by the screen test rather than by reading.)
     *
     * <p>Empty when nobody is named. Every tenancy reserved through the app has a tenant (the
     * aggregate refuses otherwise), but a row projected before pm_tenancy_party existed has none,
     * and an initial invented for it would be an invented person.
     */
    private static String initials(List<ContactDetails> tenants) {
        return tenants.stream()
            .findFirst()
            .map(contact -> letter(contact.givenName()) + letter(contact.surname()))
            .orElse("");
    }

    /** Blank rather than an exception for an absent or empty name half — a contact may have one. */
    private static String letter(String name) {
        return name == null || name.isBlank() ? "" : name.substring(0, 1).toUpperCase();
    }

    /**
     * Contact id to display name, asked once per distinct contact across the whole page.
     *
     * <p>An unknown or foreign contact is simply absent — {@link ContactDirectory#find} is already
     * workspace-scoped and answers empty for both, which is the undifferentiated answer that keeps
     * it from being an oracle. This screen does not distinguish them either.
     */
    private Map<UUID, ContactDetails> names(UUID workspaceId, List<TenancyBoardRow> register) {
        var names = new HashMap<UUID, ContactDetails>();
        register.stream()
            .flatMap(row -> row.tenantContactIds().stream())
            .distinct()
            .forEach(contactId -> contacts.find(workspaceId, contactId)
                .ifPresent(contact -> names.put(contactId, contact)));
        return names;
    }

    /**
     * The header's one line: how many are running, and how many have finished.
     *
     * <p>Both numbers count this page's own rows, so the header states what is already on screen.
     * The mockup's second figure is empty units, which this screen never queries — it lists
     * tenancies, and a unit with no tenancy has no row here to be counted. Reporting ended ones
     * instead keeps the summary a summary of the table beneath it rather than a fact from
     * somewhere else that nobody can check against what they are looking at.
     */
    private static String summary(List<TenancyBoardRow> register) {
        long active = register.stream().filter(row -> row.state() == Tenancy.State.ACTIVE).count();
        long ended = register.stream().filter(row -> row.state() == Tenancy.State.ENDED).count();
        return PolishPlural.count(active, "aktywny", "aktywne", "aktywnych")
            + " · " + PolishPlural.count(ended, "zakończony", "zakończone", "zakończonych");
    }
}
