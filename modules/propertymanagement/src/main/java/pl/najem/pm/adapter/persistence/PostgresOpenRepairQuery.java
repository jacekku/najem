package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.OpenRepair;
import pl.najem.pm.application.OpenRepairQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@link OpenRepairQuery} over pm_repair.
 *
 * <p>{@code completed_on is null} matches the partial index pm_repair_open_idx, which exists for
 * exactly this question — "what is still open on this flat" — so the predicate is not incidental
 * and must not be rephrased into something the index cannot serve.
 */
@Repository
public class PostgresOpenRepairQuery implements OpenRepairQuery {

    private final JdbcTemplate jdbc;

    public PostgresOpenRepairQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OpenRepair> openRepairs(UUID workspaceId) {
        return jdbc.query("""
            select repair_id, scope, asset_id, description, statutory_duty_hint, reported_on
            from pm_repair
            where workspace_id = ? and completed_on is null
            order by reported_on
            """,
            (rs, n) -> new OpenRepair(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getObject(6, LocalDate.class)),
            workspaceId);
    }
}
