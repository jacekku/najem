package pl.najem.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import pl.najem.acc.application.ArrearsBoardProjection;
import pl.najem.acc.application.InvoiceRepository;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.pm.application.TenancyBoardProjection;
import pl.najem.pm.application.TenancyBoardRow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raporty: one screen, one tab per report.
 *
 * <p>Two reports were two sidebar items until the rail was cut to 4 + 4. Zaległości is built and
 * is what the screen opens on; Rozliczenia właścicieli is not, and says so rather than being
 * omitted — the same rule the unbuilt rail items follow, for the same reason: a manager should be
 * able to see the shape of the product without being able to click into a route that goes nowhere.
 *
 * <p>The arrears board is the one screen where a colour carries a statutory consequence. The
 * colour is rendered exactly as given and never derived here. {@code brightRed} means a whole rent
 * period has elapsed unpaid and the art. 11 termination counter is running, and a template that
 * recomputed it from amounts and dates would be a second implementation of a rule with
 * consequences outside this application.
 *
 * <p>Takes the enum rather than the wire string, on Accounting's own argument: a colour they stop
 * emitting then breaks this compile instead of leaving a branch here that renders forever.
 *
 * <p>Which tab is showing is decided here and nowhere else: the accounting module has no opinion
 * about tab strips, and the only thing crossing the boundary is still
 * {@link ArrearsBoardProjection#forWorkspace}. The board is queried only when its own tab is the
 * one being rendered.
 */
@Controller
public class ReportsScreenController {

    /**
     * The tab labels, which double as the {@code active} value the {@code tabs} fragment compares
     * against each item's own label. Named constants rather than two loose strings, because the
     * template repeats them in its item list and a drift between the two renders a tab strip with
     * nothing selected — visible, but easy to look past.
     */
    static final String ARREARS = "Zaległości";
    static final String OWNER_SETTLEMENTS = "Rozliczenia właścicieli";

    /**
     * The {@code ?tab=} value that selects the unbuilt tab.
     *
     * <p>An absent {@code tab} opens Zaległości, because that is what {@code /reports} means. An
     * unrecognised one is a 404, and deliberately not a silent fall back to Zaległości: this screen
     * names which report is showing only in a tab label and a heading, so a typo would open the
     * arrears board while the URL claimed something else, and the manager would read a real report
     * as an answer to the question they did not ask. A wrong report that looks right is the one
     * failure mode a report screen must not have.
     */
    static final String OWNERS_PARAM = "wlasciciele";

    /**
     * One row of the arrears board, as the screen reads it.
     *
     * <p><b>The colour is accounting's and everything else is composition.</b> The name comes from
     * Contacts through PM's party list, the unit label from Reporting through {@link TenancyLabels},
     * and the amount from {@link InvoiceRepository}. Nothing here is derived from the colour and the
     * colour is not derived from anything here — a screen that recomputed it would be a second
     * implementation of a rule with consequences outside this application.
     *
     * @param tenant  every tenant on the tenancy, comma-separated. Blank when the tenancy's parties
     *                were never projected, which the template renders as "Bez najemcy" rather than
     *                as an empty cell.
     * @param where   the unit, or null when the occupancy projection does not place this tenancy —
     *                an ended one, or one that has not started. Null rather than the id: the row's
     *                point is its colour, and a UUID in a name column is the failure
     *                {@link TenancyLabels} exists to prevent.
     * @param owed    POSITIVE, as accounting reports it. This column is headed "Zaległość" and a
     *                negative number under that heading reads as a credit — the opposite sign from
     *                the register's "Saldo", and deliberately so, because the two columns ask
     *                different questions.
     */
    public record Row(UUID tenancyId, String tenant, String where, BigDecimal owed,
                      ArrearsColour colour) {
    }

    private final ArrearsBoardProjection board;
    private final TenancyLabels labels;
    private final TenancyBoardProjection tenancies;
    private final InvoiceRepository invoices;
    private final ContactDirectory contacts;
    private final Clock clock;

    public ReportsScreenController(ArrearsBoardProjection board, TenancyLabels labels,
                                   TenancyBoardProjection tenancies, InvoiceRepository invoices,
                                   ContactDirectory contacts, Clock clock) {
        this.board = board;
        this.labels = labels;
        this.tenancies = tenancies;
        this.invoices = invoices;
        this.contacts = contacts;
        this.clock = clock;
    }

    @GetMapping("/reports")
    public String reports(@RequestParam(name = "tab", required = false) String tab,
                          WebWorkspace workspace, Model model) {
        if (tab != null && !OWNERS_PARAM.equals(tab)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie ma takiego raportu: " + tab);
        }
        boolean owners = OWNERS_PARAM.equals(tab);
        model.addAttribute("activeTab", owners ? OWNER_SETTLEMENTS : ARREARS);
        if (!owners) {
            model.addAttribute("rows", rows(workspace.workspaceId()));
        }
        return "reports";
    }

    /**
     * The board, named and costed.
     *
     * <p>It used to be two columns — a label and a colour — because the projection carries only a
     * tenancy id and a colour. Prototype v2 draws the board a manager actually works from: who owes,
     * where, how much, and how bad it is. Every one of those was already answerable and none of them
     * was being asked.
     *
     * <p><b>Four workspace-wide reads and one bounded fan-out, not five per row.</b> The board, the
     * register, the balances and the labels are each fetched whole and joined in memory; contacts is
     * asked once per distinct person across the page, because {@link ContactDirectory} offers no
     * bulk read. The same arrangement — and the same reason for it — as
     * {@code TenanciesScreenController}: a per-row query multiplies with the agency's size, which is
     * precisely where a portfolio screen goes wrong.
     */
    private List<Row> rows(UUID workspaceId) {
        var unitLabels = labels.forWorkspace(workspaceId, LocalDate.now(clock));
        var outstanding = invoices.outstandingByTenancy(workspaceId);
        var register = tenancies.forWorkspace(workspaceId).stream()
            .collect(java.util.stream.Collectors.toMap(TenancyBoardRow::tenancyId, row -> row,
                // A workspace cannot hold two tenancies with one id; the merge function exists
                // because toMap demands one, and keeping the first is the arbitrary-but-stable
                // choice. A duplicate here would be a projection bug, not a screen concern.
                (first, second) -> first));
        var names = names(workspaceId, register.values());

        return board.forWorkspace(workspaceId).stream()
            .map(row -> new Row(row.tenancyId(),
                tenantOf(register.get(row.tenancyId()), names),
                unitLabels.get(row.tenancyId()),
                outstanding.getOrDefault(row.tenancyId(), BigDecimal.ZERO),
                row.colour()))
            .toList();
    }

    /** Blank for a tenancy the register does not know, or one whose parties were never projected —
     *  both real states, and both rendered by the template as "Bez najemcy" rather than guessed at. */
    private static String tenantOf(TenancyBoardRow row, Map<UUID, ContactDetails> names) {
        if (row == null) {
            return "";
        }
        return String.join(", ", row.tenantContactIds().stream()
            .map(names::get)
            .filter(java.util.Objects::nonNull)
            .map(contact -> (contact.givenName() + " " + contact.surname()).trim())
            .filter(name -> !name.isBlank())
            .toList());
    }

    private Map<UUID, ContactDetails> names(UUID workspaceId,
                                            java.util.Collection<TenancyBoardRow> register) {
        var names = new HashMap<UUID, ContactDetails>();
        register.stream()
            .flatMap(row -> row.tenantContactIds().stream())
            .distinct()
            .forEach(contactId -> contacts.find(workspaceId, contactId)
                .ifPresent(contact -> names.put(contactId, contact)));
        return names;
    }

    /**
     * The old single-report path, kept working.
     *
     * <p>{@code /report} was the arrears board's own URL for as long as it existed, so it is in
     * bookmarks and in anything that linked to it. A redirect costs one mapping; a 404 costs a
     * manager the report. The sidebar marks Raporty active for this path too, so the rail does not
     * blank out while the browser follows the redirect.
     */
    @GetMapping("/report")
    public String legacyReportPath() {
        return "redirect:/reports";
    }
}
