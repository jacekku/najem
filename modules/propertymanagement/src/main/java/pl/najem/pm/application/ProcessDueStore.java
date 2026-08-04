package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Durable timers for the PM process managers — rows, not in-memory schedules, so a restart
 * never loses a pending activation and a repeated sweep never fires one twice.
 */
@Component
public class ProcessDueStore {

    private final JdbcTemplate jdbc;

    public ProcessDueStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Re-arming an already-fired timer clears fired_at — used when a due date moves. */
    public void arm(String kind, UUID subjectId, LocalDate dueOn) {
        jdbc.update("insert into pm_process_due(kind, subject_id, due_on) values (?,?,?) "
            + "on conflict (kind, subject_id) do update set due_on = excluded.due_on, fired_at = null",
            kind, subjectId, dueOn);
    }

    public List<UUID> due(String kind, LocalDate on) {
        return jdbc.queryForList("select subject_id from pm_process_due "
            + "where kind = ? and due_on <= ? and fired_at is null order by due_on",
            UUID.class, kind, on);
    }

    public void markFired(String kind, UUID subjectId) {
        jdbc.update("update pm_process_due set fired_at = now() where kind = ? and subject_id = ?",
            kind, subjectId);
    }

    public void disarm(String kind, UUID subjectId) {
        jdbc.update("delete from pm_process_due where kind = ? and subject_id = ?", kind, subjectId);
    }
}
