package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.Warning;
import pl.najem.acc.application.WarningRepository;
import pl.najem.acc.application.WarningToRaise;
import pl.najem.acc.domain.WarningKind;

import java.util.List;
import java.util.UUID;

/** {@link WarningRepository} over acc_warning. */
@Repository
public class PostgresWarningRepository implements WarningRepository {

    private final JdbcTemplate jdbc;

    public PostgresWarningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void raise(UUID workspaceId, UUID tenancyId, List<WarningToRaise> warnings) {
        jdbc.batchUpdate("""
            insert into acc_warning(warning_id, workspace_id, tenancy_id, kind, detail)
            values (?,?,?,?,?)
            """, warnings.stream()
            .map(warning -> new Object[] {UUID.randomUUID(), workspaceId, tenancyId,
                warning.kind().wireName(), warning.detail()})
            .toList());
    }

    @Override
    public List<Warning> unseen(UUID workspaceId) {
        return jdbc.query("""
            select warning_id, tenancy_id, kind, detail from acc_warning
            where workspace_id = ? and not seen order by raised_at
            """, (rs, i) -> new Warning(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                WarningKind.of(rs.getString(3)), rs.getString(4)), workspaceId);
    }

    @Override
    public void markSeen(UUID workspaceId, UUID warningId) {
        jdbc.update("update acc_warning set seen = true where workspace_id = ? and warning_id = ?",
            workspaceId, warningId);
    }
}
