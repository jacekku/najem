package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.ArrearsColour;

import java.util.List;
import java.util.UUID;

/**
 * Reading the arrears board.
 *
 * <p>Exists so the UI has something in the application layer to call. The board was only ever a
 * {@code jdbc.query} inside a controller, which meant the one screen that needs it could reach it
 * only by going through the adapter or by talking to this module's tables directly — and the second
 * is what the plan forbids.
 *
 * <p>The colour is returned as the enum rather than as the string on the row. A screen that reads
 * {@code "brightRed"} as an opaque token can render it, but it cannot be checked at compile time
 * against the set of colours that actually exist, and a colour this module stopped emitting would go
 * on rendering until somebody noticed the legend was wrong.
 */
@Service
@Transactional(readOnly = true)
public class ArrearsBoardQuery {

    /** One tenancy's standing. */
    public record Row(UUID tenancyId, ArrearsColour colour) {}

    private final JdbcTemplate jdbc;

    public ArrearsBoardQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> forWorkspace(UUID workspaceId) {
        return jdbc.query("""
            select tenancy_id, status from acc_tenancy_status
            where workspace_id = ? order by tenancy_id
            """, (rs, i) -> new Row(rs.getObject(1, UUID.class), ArrearsColour.of(rs.getString(2))),
            workspaceId);
    }
}
