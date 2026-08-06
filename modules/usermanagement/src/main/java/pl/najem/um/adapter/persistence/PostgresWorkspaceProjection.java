package pl.najem.um.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.um.application.WorkspaceProjection;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public class PostgresWorkspaceProjection implements WorkspaceProjection {

    private final JdbcTemplate jdbc;

    public PostgresWorkspaceProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(UUID workspaceId, String name, LocalDate on) {
        jdbc.update("insert into um_workspace(workspace_id, name, created_on) values (?,?,?)",
            workspaceId, name, on);
    }

    @Override
    public void rename(UUID workspaceId, String name) {
        jdbc.update("update um_workspace set name = ? where workspace_id = ?", name, workspaceId);
    }
}
