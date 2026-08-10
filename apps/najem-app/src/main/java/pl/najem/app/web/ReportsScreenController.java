package pl.najem.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import pl.najem.acc.application.ArrearsBoardProjection;

import java.time.Clock;
import java.time.LocalDate;

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

    private final ArrearsBoardProjection board;
    private final TenancyLabels labels;
    private final Clock clock;

    public ReportsScreenController(ArrearsBoardProjection board, TenancyLabels labels, Clock clock) {
        this.board = board;
        this.labels = labels;
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
            model.addAttribute("rows", board.forWorkspace(workspace.workspaceId()));
            model.addAttribute("labels",
                labels.forWorkspace(workspace.workspaceId(), LocalDate.now(clock)));
        }
        return "reports";
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
