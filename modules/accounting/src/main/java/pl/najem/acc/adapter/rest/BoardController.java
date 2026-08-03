package pl.najem.acc.adapter.rest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/acc")
public class BoardController {

    private final JdbcTemplate jdbc;

    public BoardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/board")
    public List<Map<String, Object>> board() {
        return jdbc.query("select tenancy_id, status from acc_tenancy_status order by tenancy_id",
            (rs, i) -> Map.of("tenancyId", rs.getObject(1), "status", rs.getString(2)));
    }
}
