package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.ProcessDueRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** pm_process_due. The upsert is what makes re-arming a moved due date safe. */
@Repository
public class PostgresProcessDueRepository implements ProcessDueRepository {

    private final JdbcTemplate jdbc;

    public PostgresProcessDueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void arm(String kind, UUID subjectId, LocalDate dueOn) {
        jdbc.update("insert into pm_process_due(kind, subject_id, due_on) values (?,?,?) "
            + "on conflict (kind, subject_id) do update set due_on = excluded.due_on, fired_at = null",
            kind, subjectId, dueOn);
    }

    @Override
    public List<UUID> due(String kind, LocalDate on) {
        return jdbc.queryForList("select subject_id from pm_process_due "
            + "where kind = ? and due_on <= ? and fired_at is null order by due_on",
            UUID.class, kind, on);
    }

    @Override
    public void markFired(String kind, UUID subjectId) {
        jdbc.update("update pm_process_due set fired_at = now() where kind = ? and subject_id = ?",
            kind, subjectId);
    }

    @Override
    public void disarm(String kind, UUID subjectId) {
        jdbc.update("delete from pm_process_due where kind = ? and subject_id = ?", kind, subjectId);
    }
}
