package pl.najem.reporting.adapter.rest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.reporting.WorkspaceContext;
import pl.najem.reporting.application.ProjectionRunner;
import pl.najem.reporting.application.ProjectionStatus;
import pl.najem.reporting.application.PropertyOccupancy;
import pl.najem.reporting.application.TimelineQuery;
import pl.najem.reporting.application.UnitBoardQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reporting's read surface. Everything here is a query over a projection — there is no command,
 * because Reporting owns no decisions.
 * <p>
 * A caller sees only their own workspace, enforced in every query rather than by a guard: an
 * unknown subject and another agency's subject are indistinguishable, both returning an empty
 * timeline rather than a 403 that would confirm the subject exists.
 */
@RestController
@RequestMapping("/api/reporting")
public class ReportingController {

    private final TimelineQuery timelines;
    private final PropertyOccupancy occupancy;
    private final UnitBoardQuery board;
    private final ProjectionRunner runner;
    private final ProjectionStatus status;

    public ReportingController(TimelineQuery timelines, PropertyOccupancy occupancy,
                               UnitBoardQuery board, ProjectionRunner runner,
                               ProjectionStatus status) {
        this.timelines = timelines;
        this.occupancy = occupancy;
        this.board = board;
        this.runner = runner;
        this.status = status;
    }

    /**
     * How far behind each projection is, so a caller rendering a board can say so.
     * <p>
     * Not workspace-scoped: lag is a property of the projector, not of anyone's data, and it
     * exposes no facts about any agency — only how many events remain unapplied.
     */
    @GetMapping("/status")
    public List<ProjectionStatus.Status> status() {
        return status.all();
    }

    @GetMapping("/tenancies/{tenancyId}/timeline")
    public List<TimelineQuery.Entry> tenancyTimeline(
        @RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
        @PathVariable UUID tenancyId) {
        return timelines.forSubject(WorkspaceContext.resolve(workspaceId), "tenancy", tenancyId);
    }

    @GetMapping("/units/{unitId}/timeline")
    public List<TimelineQuery.Entry> unitTimeline(
        @RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
        @PathVariable UUID unitId) {
        return timelines.forSubject(WorkspaceContext.resolve(workspaceId), "unit", unitId);
    }

    @GetMapping("/properties/{propertyId}/occupancy")
    public PropertyOccupancy.Counts propertyOccupancy(
        @RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
        @PathVariable UUID propertyId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return occupancy.countsFor(WorkspaceContext.resolve(workspaceId), propertyId, orToday(asOf));
    }

    /**
     * The Unit Board's rows, current and next occupant resolved server-side.
     * <p>
     * <b>Ownership of this endpoint is pending a coordinator ruling</b> (najem-build seq 101):
     * whether the Unit Board is served from Reporting's projections or from a PM read side. It is
     * self-contained — dropping it removes no other behaviour — so it is here to be declined rather
     * than to pre-empt the decision.
     */
    @GetMapping("/units")
    public List<UnitBoardQuery.Row> unitsInProperty(
        @RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
        @RequestParam UUID propertyId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return board.forProperty(WorkspaceContext.resolve(workspaceId), propertyId, orToday(asOf));
    }

    /**
     * Discards a projection and replays it from the beginning of history — the operator action that
     * makes a read model safe to change shape.
     * <p>
     * Deliberately unauthenticated only because nothing here is yet: it must be behind an admin role
     * the moment an issuer is configured, since replaying every projection is a denial of service
     * anyone could trigger. Flagged rather than gated, because inventing a role here would duplicate
     * a decision UserManagement owns.
     */
    @PostMapping("/projections/{name}/rebuild")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rebuild(@PathVariable String name) {
        runner.rebuild(name);
    }

    private static LocalDate orToday(LocalDate asOf) {
        return asOf == null ? LocalDate.now() : asOf;
    }
}
