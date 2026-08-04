package pl.najem.app.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import pl.najem.reporting.application.ProjectionStatus;

/**
 * Tells every screen whether the read models it is about to render are caught up.
 *
 * <p>Boards here are served from Reporting's projections, which are eventually consistent. A
 * briefly stale board is the trade that buys; a <em>silently</em> stale one is a screen that lies —
 * and the two look identical, because a stalled projector renders as an empty or short list rather
 * than as an error. A manager who has just created a property and does not see it cannot tell
 * "not yet" from "it didn't work".
 *
 * <p>najem-pm made this the condition for accepting an eventually consistent board (najem-build
 * seq 110) and they were right to make it a condition. This is the screen half of it.
 *
 * <p>Scoped to this package, like {@link WebErrorAdvice}: it must not attach itself to any
 * module's API responses.
 */
@ControllerAdvice(basePackageClasses = ProjectionFreshnessAdvice.class)
public class ProjectionFreshnessAdvice {

    private final ProjectionStatus projections;

    public ProjectionFreshnessAdvice(ProjectionStatus projections) {
        this.projections = projections;
    }

    /**
     * True when anything Reporting serves is behind. Deliberately one flag rather than per-board
     * lag: which projection feeds which screen is Reporting's business, and a screen claiming
     * "this particular board is current" would be asserting a mapping it does not own.
     */
    @ModelAttribute("projectionsBehind")
    public boolean projectionsBehind() {
        return projections.all().stream().anyMatch(status -> !status.caughtUp());
    }
}
