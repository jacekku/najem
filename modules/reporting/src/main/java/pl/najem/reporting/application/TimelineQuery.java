package pl.najem.reporting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Reads a subject's story back. Every query filters on workspace: there is no unscoped read. */
@Component
public class TimelineQuery {

    public record Entry(LocalDate occurredOn, String kind, String summary) {
    }

    private final JdbcTemplate jdbc;

    public TimelineQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ordered by when the fact applies, then by {@code global_seq} to break ties — two facts on one
     * day read in the order they were recorded, which is the only tiebreak that is not arbitrary.
     */
    public List<Entry> forSubject(UUID workspaceId, String level, UUID subjectId) {
        return jdbc.query("""
            select occurred_on, kind, summary from reporting_timeline_entry
            where workspace_id = ? and level = ? and subject_id = ?
            order by occurred_on, global_seq
            """,
            (rs, i) -> new Entry(rs.getDate("occurred_on").toLocalDate(),
                rs.getString("kind"), rs.getString("summary")),
            workspaceId, level, subjectId);
    }
}
