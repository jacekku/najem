package pl.najem.reporting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * How far behind each projection is.
 * <p>
 * A read model that is briefly stale is fine — that is the trade eventual consistency buys. A read
 * model that is <em>silently</em> stale because the projector stalled is a screen that lies, and
 * Reporting already has a test proving a stalled projector looks idle rather than broken. So the
 * lag is published: a caller that renders a board can say "as of N events ago" rather than present
 * stale data as current.
 * <p>
 * Requested by najem-pm (najem-build seq 110) as the condition for accepting an eventually
 * consistent Unit Board, and they were right to make it a condition rather than a nicety.
 */
@Component
public class ProjectionStatus {

    /**
     * {@code eventsBehind} counts only events Reporting is allowed to see — counting the whole
     * table would report permanent lag for every event on a stream the allowlist excludes, which
     * would make a healthy projector look permanently broken.
     * <p>
     * <b>{@code lastGlobalSeq} was here and is deliberately gone</b> (@najem-reviewer, najem-build
     * seq 358). This endpoint is about to be called by every screen on every render, and that field
     * is a monotonic counter over <em>all</em> tenants' writes: an agency differencing it learns the
     * platform's total write volume and roughly when other agencies are busy. No row crosses the
     * boundary, so it is a side channel rather than a disclosure — but it answers nothing anyone
     * asked, and the moment to drop a field is while the shape is being chosen rather than after
     * five screens depend on it.
     */
    public record Status(String projection, long eventsBehind) {

        public boolean caughtUp() {
            return eventsBehind == 0;
        }
    }

    private final JdbcTemplate jdbc;

    public ProjectionStatus(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Status> all() {
        var streams = String.join(",", java.util.Collections.nCopies(EventFeed.ALLOWED_STREAMS.size(), "?"));
        var args = new java.util.ArrayList<>(EventFeed.ALLOWED_STREAMS);
        return jdbc.query("""
            select c.projection_name,
                   c.last_global_seq,
                   (select count(*) from events e
                      where e.global_seq > c.last_global_seq and e.stream_type in (%s)) as events_behind
            from reporting_checkpoint c
            order by c.projection_name
            """.formatted(streams),
            (rs, i) -> new Status(rs.getString("projection_name"), rs.getLong("events_behind")),
            args.toArray());
    }
}
