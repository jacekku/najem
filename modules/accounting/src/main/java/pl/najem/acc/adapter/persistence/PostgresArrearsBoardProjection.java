package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.ArrearsBoardProjection;
import pl.najem.acc.domain.ArrearsColour;

import java.util.List;
import java.util.UUID;

/** {@link ArrearsBoardProjection} over acc_tenancy_status. */
@Repository
public class PostgresArrearsBoardProjection implements ArrearsBoardProjection {

    private final JdbcTemplate jdbc;

    public PostgresArrearsBoardProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> forWorkspace(UUID workspaceId) {
        return jdbc.query("""
            select tenancy_id, status, full_periods_in_arrears from acc_tenancy_status
            where workspace_id = ? order by tenancy_id
            """, (rs, i) -> new Row(rs.getObject(1, UUID.class), ArrearsColour.of(rs.getString(2)),
                rs.getInt(3)), workspaceId);
    }
}
