package pl.najem.reporting.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pl.najem.reporting.application.CheckpointStore;

/** {@link CheckpointStore} over {@code reporting_checkpoint}. */
@Component
public class PostgresCheckpointStore implements CheckpointStore {

    private final JdbcTemplate jdbc;

    public PostgresCheckpointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code queryForList} rather than {@code queryForObject}, which throws on no row. A projection
     * that has never run is the normal first-poll case, not an error.
     */
    @Override
    public long positionOf(String projectionName) {
        var found = jdbc.queryForList(
            "select last_global_seq from reporting_checkpoint where projection_name = ?",
            Long.class, projectionName);
        return found.isEmpty() ? 0L : found.get(0);
    }

    @Override
    public void advanceTo(String projectionName, long globalSeq) {
        jdbc.update("""
            insert into reporting_checkpoint(projection_name, last_global_seq) values (?,?)
            on conflict (projection_name) do update set last_global_seq = excluded.last_global_seq
            """, projectionName, globalSeq);
    }

    @Override
    public void reset(String projectionName) {
        jdbc.update("""
            insert into reporting_checkpoint(projection_name, last_global_seq) values (?, 0)
            on conflict (projection_name) do update set last_global_seq = 0
            """, projectionName);
    }
}
