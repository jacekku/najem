package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pl.najem.reporting.application.SearchQuery;

import java.util.List;

/**
 * One box in the masthead, results grouped by what they are.
 *
 * <p>Grouped rather than ranked: Reporting deliberately does not rank, and inventing an order here
 * would be the UI asserting a relevance it has no basis for. A property and a unit that both match
 * are two different kinds of answer, and which one a person wants is not something a score knows.
 *
 * <p>A blank term returns nothing rather than everything. Reporting already decides that, and this
 * renders the empty result as a prompt rather than as "found nothing" — those read very
 * differently to someone who has not typed anything yet.
 */
@Controller
public class SearchScreenController {

    private final SearchQuery search;

    public SearchScreenController(SearchQuery search) {
        this.search = search;
    }

    @GetMapping("/search")
    public String search(@RequestParam(name = "q", required = false) String term,
                         WebWorkspace workspace, Model model) {
        var hits = search.search(workspace.workspaceId(), term);
        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        // Grouped HERE rather than by a selection expression in the template. The first version
        // wrote `hits.?[kind == 'property']`, which cannot resolve `kind` on a record and threw at
        // render time — and every test passed, because the only guard around it is
        // `!hits.isEmpty()` and no test ever produced a hit. An expression that fails only when
        // there is something to show is the worst place to put one.
        model.addAttribute("properties", ofKind(hits, "property"));
        model.addAttribute("units", ofKind(hits, "unit"));
        return "search";
    }

    /** Package-private so it can be tested without a database — see {@code SearchGroupingTest}. */
    static List<SearchQuery.Hit> ofKind(List<SearchQuery.Hit> hits, String kind) {
        return hits.stream().filter(hit -> kind.equals(hit.kind())).toList();
    }
}
