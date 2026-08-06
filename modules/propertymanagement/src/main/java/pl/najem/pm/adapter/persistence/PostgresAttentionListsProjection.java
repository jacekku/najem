package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.AttentionListsProjection;
import pl.najem.pm.application.TenancyAttentionRow;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The three deadline lists over pm_tenancy, joined to the unit and property that name them. */
@Repository
public class PostgresAttentionListsProjection implements AttentionListsProjection {

    private final JdbcTemplate jdbc;

    public PostgresAttentionListsProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TenancyAttentionRow> startingSoon(UUID workspaceId, LocalDate through) {
        return rows("t.start_date", "t.state = 'RESERVED' and t.start_date is not null",
            workspaceId, through);
    }

    @Override
    public List<TenancyAttentionRow> endingSoon(UUID workspaceId, LocalDate through) {
        return rows("t.end_date", "t.state = 'ACTIVE' and t.end_date is not null",
            workspaceId, through);
    }

    @Override
    public List<TenancyAttentionRow> insuranceExpiring(UUID workspaceId, LocalDate through) {
        return rows("t.insurance_valid_to",
            "t.state = 'ACTIVE' and t.insurance_valid_to is not null", workspaceId, through);
    }

    /**
     * The three lists differ only in which date they watch and which state qualifies, so they share
     * one query rather than three that can drift apart. dateColumn and extraWhere are fixed strings
     * chosen in this class — never caller input.
     */
    private List<TenancyAttentionRow> rows(String dateColumn, String extraWhere,
                                           UUID workspaceId, LocalDate through) {
        return jdbc.query("""
            select t.tenancy_id, t.unit_id, u.name, p.address, %s
            from pm_tenancy t
            join pm_unit u on u.unit_id = t.unit_id
            join pm_property p on p.property_id = u.property_id
            where t.workspace_id = ? and %s and %s <= ?
            order by %s
            """.formatted(dateColumn, extraWhere, dateColumn, dateColumn),
            (rs, n) -> new TenancyAttentionRow(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDate.class)),
            workspaceId, through);
    }
}
