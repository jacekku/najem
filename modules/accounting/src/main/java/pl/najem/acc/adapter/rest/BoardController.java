package pl.najem.acc.adapter.rest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/acc")
public class BoardController {

    private final JdbcTemplate jdbc;

    public BoardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The arrears board for one workspace. The header is required, as on every read: a board served
     * to a caller who named no agency is another agency's portfolio, tenancy by tenancy, with its
     * arrears next to it.
     */
    @GetMapping("/board")
    public List<Map<String, Object>> board(@RequestHeader("X-Workspace-Id") UUID workspaceId) {
        return jdbc.query("""
            select tenancy_id, status from acc_tenancy_status
            where workspace_id = ? order by tenancy_id
            """, (rs, i) -> Map.of("tenancyId", rs.getObject(1), "status", rs.getString(2)),
            workspaceId);
    }
}
