package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.WarningKind;

import java.util.List;
import java.util.UUID;

/**
 * The warning register. Warnings are raised inside the transaction that caused them, so a rolled
 * back charge takes its warnings with it and a committed one can never lose them.
 */
@Service
@Transactional
public class WarningService {

    private final JdbcTemplate jdbc;

    public WarningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void raise(UUID workspaceId, UUID tenancyId, List<Warning> warnings) {
        for (Warning warning : warnings) {
            jdbc.update("""
                insert into acc_warning(warning_id, workspace_id, tenancy_id, kind, detail)
                values (?,?,?,?,?)
                """, UUID.randomUUID(), workspaceId, tenancyId, warning.kind().wireName(),
                warning.detail());
        }
    }

    public List<Warning> unseen(UUID workspaceId) {
        return jdbc.query("""
            select warning_id, tenancy_id, kind, detail from acc_warning
            where workspace_id = ? and not seen order by raised_at
            """, (rs, i) -> new Warning(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                WarningKind.of(rs.getString(3)), rs.getString(4)), workspaceId);
    }

    /** Another agency's warning is not yours to silence, so the update is workspace-scoped. */
    public void markSeen(UUID workspaceId, UUID warningId) {
        jdbc.update("update acc_warning set seen = true where workspace_id = ? and warning_id = ?",
            workspaceId, warningId);
    }
}
