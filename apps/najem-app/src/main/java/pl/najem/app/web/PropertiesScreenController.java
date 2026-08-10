package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import pl.najem.reporting.application.PropertyBoardQuery;

import java.time.Clock;
import java.time.LocalDate;

/**
 * The agency's portfolio — the entry point, and the only screen that yields the property ids every
 * other reporting call needs.
 *
 * <p>One query for the whole page. Asking Reporting for each property's occupancy in a loop is the
 * N+1 that {@code UnitBoardQuery} exists to avoid one level down, and a portfolio list is exactly
 * where it multiplies.
 */
@Controller
public class PropertiesScreenController {

    private final PropertyBoardQuery properties;
    private final Clock clock;

    public PropertiesScreenController(PropertyBoardQuery properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/properties")
    public String properties(WebWorkspace workspace, Model model) {
        var board = properties.forWorkspace(workspace.workspaceId(), LocalDate.now(clock));
        model.addAttribute("properties", board);
        model.addAttribute("portfolio", portfolio(board));
        return "properties";
    }

    /**
     * The header's one line of summary: how many properties, and how many units in them.
     *
     * <p>Both numbers are the page's own rows — the count of them, and the Razem column added up —
     * so this states what is already visible rather than introducing a fact only the header knows.
     * The handoff draws a potential-rent figure here too; nothing this screen queries returns rent.
     */
    private static String portfolio(java.util.List<PropertyBoardQuery.Row> board) {
        long units = board.stream().mapToLong(row -> row.occupancy().total()).sum();
        return board.size() + " " + polish(board.size(), "nieruchomość", "nieruchomości", "nieruchomości")
            + " · " + units + " " + polish(units, "lokal", "lokale", "lokali");
    }

    /**
     * Polish has three plural forms, not two, and the rule is on the last two digits.
     *
     * <p>One (1 lokal), few for a count ending in 2–4 (2 lokale, 23 lokale) — except the teens,
     * where 12–14 take the many form (12 lokali, not "12 lokale") — and many for everything else
     * (5 lokali, 0 lokali). English's singular/plural applied here reads as broken Polish on most
     * counts rather than on an edge case, and the teens exception is the half of the rule that gets
     * left out; {@code PolishPluralTest} pins both.
     */
    static String polish(long count, String one, String few, String many) {
        long lastTwo = Math.abs(count) % 100;
        long last = Math.abs(count) % 10;
        if (count == 1) {
            return one;
        }
        if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) {
            return few;
        }
        return many;
    }
}
