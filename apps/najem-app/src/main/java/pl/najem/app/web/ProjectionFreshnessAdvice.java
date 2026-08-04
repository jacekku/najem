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
 * <p><b>Half-covered, deliberately, and the other half is not in this package.</b> A projection
 * gets a checkpoint row only once it has applied something, so one that has <em>never run</em> is
 * absent from {@code ProjectionStatus.all()} rather than reported as behind. This class can see
 * the case where the whole list is empty and treats it as behind; it cannot see one projection
 * missing out of four, because it does not know how many there ought to be. That needs the
 * expected set, which the runner in Reporting holds — filed at najem-build seq 409. Until then
 * this is a floor on staleness, not a proof of freshness.
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
        var statuses = projections.all();
        // No rows at all is NOT "everything is current" — it is "nothing has ever been projected",
        // which is the worst state this banner exists for and the one where the naive
        // `anyMatch` is silent, because anyMatch over an empty list is false. A projection only
        // gets a checkpoint row once it applies something, so a fresh database or a runner that
        // never started produces exactly this. No evidence of lag is not evidence of no lag.
        // (@najem-reviewer, najem-build seq 409.)
        return statuses.isEmpty() || statuses.stream().anyMatch(status -> !status.caughtUp());
    }
}
