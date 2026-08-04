package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import pl.najem.reporting.application.TimelineQuery;

import java.util.UUID;

/**
 * What happened to one tenancy, or to one unit, in the order it applies.
 *
 * <p>Exists because the units screen already links here. A link to a route that does not exist is
 * the same class of defect as a number that came from nowhere — it tells a person something is
 * there when nothing is.
 *
 * <p>Renders Reporting's entries verbatim. The summaries are written by the projector, which is
 * where the domain knowledge lives; composing a sentence here would be this screen inventing a
 * second account of the same events.
 */
@Controller
public class TimelineScreenController {

    /** The projection's own level names. Passed through, never invented here. */
    private static final String TENANCY = "tenancy";
    private static final String UNIT = "unit";

    private final TimelineQuery timelines;

    public TimelineScreenController(TimelineQuery timelines) {
        this.timelines = timelines;
    }

    @GetMapping("/tenancies/{tenancyId}/timeline")
    public String tenancy(@PathVariable UUID tenancyId, WebWorkspace workspace, Model model) {
        return timeline(TENANCY, tenancyId, "Najem", workspace, model);
    }

    @GetMapping("/units/{unitId}/timeline")
    public String unit(@PathVariable UUID unitId, WebWorkspace workspace, Model model) {
        return timeline(UNIT, unitId, "Lokal", workspace, model);
    }

    private String timeline(String level, UUID subjectId, String heading,
                            WebWorkspace workspace, Model model) {
        model.addAttribute("heading", heading);
        model.addAttribute("entries",
            timelines.forSubject(workspace.workspaceId(), level, subjectId));
        return "timeline";
    }
}
