package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.ArrearsStandingProjection;
import pl.najem.acc.domain.ArrearsStanding;

import java.util.UUID;

/** {@link ArrearsStandingProjection} over acc_tenancy_status. */
@Repository
public class PostgresArrearsStandingProjection implements ArrearsStandingProjection {

    private final JdbcTemplate jdbc;

    public PostgresArrearsStandingProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An upsert, because a tenancy's standing is a single current fact rather than a history: the
     * board is re-derived on every path that moves a charge, and each derivation replaces the last.
     */
    @Override
    public void save(UUID workspaceId, UUID tenancyId, ArrearsStanding standing) {
        jdbc.update("""
            insert into acc_tenancy_status(tenancy_id, workspace_id, status, full_periods_in_arrears)
            values (?,?,?,?)
            on conflict (tenancy_id) do update
            set status = excluded.status,
                full_periods_in_arrears = excluded.full_periods_in_arrears
            """, tenancyId, workspaceId, standing.colour().wireName(),
            standing.fullPeriodsInArrears());
    }
}
