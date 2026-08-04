package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pl.najem.reporting.application.SearchQuery;

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
        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        model.addAttribute("hits", search.search(workspace.workspaceId(), term));
        return "search";
    }
}
