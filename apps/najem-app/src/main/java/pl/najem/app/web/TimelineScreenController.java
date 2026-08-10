package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pl.najem.pm.application.TenancyService;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;
import pl.najem.reporting.application.TimelineQuery;

import java.time.format.DateTimeFormatter;
import java.util.List;
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

    /**
     * `dd.mm.rrrr`, this codebase's stated date convention (see units.html/unit.html's own
     * `#numbers.formatDecimal` comments on why a format must be STATED, not inherited from the
     * server locale). Formatted here rather than with a Thymeleaf `#temporals` call: no
     * `thymeleaf-extras-java8time` is on this module's classpath, and this is the same pattern
     * {@link PaymentReferences} already uses for exactly this reason — a small formatter beside
     * the class that needs it, tested without booting anything.
     */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** What the template actually reads — {@code occurredOn} pre-formatted rather than a raw
     *  {@link java.time.LocalDate}, whose own {@code toString()} is ISO, not this app's convention. */
    public record Row(String occurredOn, String kind, String summary) {
    }

    /**
     * Package-private and static so it is testable without booting anything — the
     * {@code SearchGroupingTest}/{@code LeadFormTest} precedent for logic that does not need a
     * container to be trusted.
     */
    static List<Row> format(List<TimelineQuery.Entry> entries) {
        return entries.stream()
            .map(entry -> new Row(DATE.format(entry.occurredOn()), entry.kind(), entry.summary()))
            .toList();
    }

    private final TimelineQuery timelines;
    private final TenancyService tenancies;

    public TimelineScreenController(TimelineQuery timelines, TenancyService tenancies) {
        this.timelines = timelines;
        this.tenancies = tenancies;
    }

    @GetMapping("/tenancies/{tenancyId}/timeline")
    public String tenancy(@PathVariable UUID tenancyId, WebWorkspace workspace, Model model) {
        model.addAttribute("cancellableTenancyId",
            cancellableId(workspace.workspaceId(), tenancyId));
        return timeline(TENANCY, tenancyId, "Najem", workspace, model);
    }

    /**
     * A unit is never cancellable — {@code timeline.html} is shared with the tenancy screen above,
     * and leaving this unset is what keeps that template from offering a button that would post a
     * unit id to {@code /tenancies/{id}/cancel}.
     */
    @GetMapping("/units/{unitId}/timeline")
    public String unit(@PathVariable UUID unitId, WebWorkspace workspace, Model model) {
        return timeline(UNIT, unitId, "Lokal", workspace, model);
    }

    /**
     * A reservation made by mistake. Managers can now create these from a screen, so an undo that
     * exists only in the API is an undo nobody has.
     *
     * <p>The interest is deliberately NOT un-converted. The lead did sign and the reservation was
     * then undone; rewriting their history to say otherwise would lose that. A manager who wants them
     * back on the unit's list registers a fresh interest.
     */
    @PostMapping("/tenancies/{tenancyId}/cancel")
    public String cancel(@PathVariable UUID tenancyId, @RequestParam(required = false) String reason,
                         WebWorkspace workspace) {
        tenancies.cancelReservation(workspace.workspaceId(), tenancyId, reason == null ? "" : reason);
        return "redirect:/tenancies/" + tenancyId + "/timeline";
    }

    /**
     * Null for a tenancy that will not cancel — including one this workspace does not own or has
     * never heard of, which {@link TenancyService#isReserved} refuses by throwing rather than
     * answering false. Caught here rather than let through: the timeline itself has always rendered
     * a foreign or unknown id as an ordinary, undifferentiated empty history, and this button is not
     * reason enough to start throwing on ids that route already accepted.
     */
    private UUID cancellableId(UUID workspaceId, UUID tenancyId) {
        try {
            return tenancies.isReserved(workspaceId, tenancyId) ? tenancyId : null;
        } catch (UnknownInThisWorkspaceException e) {
            return null;
        }
    }

    private String timeline(String level, UUID subjectId, String heading,
                            WebWorkspace workspace, Model model) {
        model.addAttribute("heading", heading);
        model.addAttribute("entries",
            format(timelines.forSubject(workspace.workspaceId(), level, subjectId)));
        return "timeline";
    }
}
