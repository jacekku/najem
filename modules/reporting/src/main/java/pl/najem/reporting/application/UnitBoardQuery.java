package pl.najem.reporting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Every unit in a property with its current and next occupant already resolved, in ONE query.
 * <p>
 * Deliberately not {@code UnitOccupancy.occupantOn} called in a loop. A board renders N units at
 * once, so a per-unit primitive pushes an N+1 fan-out into whatever renders it — and a fan-out in a
 * controller or a template is a performance problem nobody owns. Resolving it here costs one query
 * plus two correlated sub-selects, and the caller gets rows it can render directly.
 * <p>
 * <b>The period predicate is the same one {@link UnitOccupancy} and {@link PropertyOccupancy} use,
 * and it was not.</b> This query said {@code not p.released} alone, which treats a period released
 * by <em>cancellation</em> and one released by <em>ending</em> as the same thing — so every tenancy
 * that genuinely ran and then finished vanished from the board rather than showing as past, and
 * {@code asOf} was meaningless for any date before the end. {@code ended_on} is the discriminator
 * and this class set it without using it.
 * <p>
 * @najem-pm diagnosed it at najem-build seq 357 as three queries over one table holding two
 * definitions of "this period counts". I had left the line knowingly wrong pending a contract
 * change from PM that turned out never to have been needed — {@code TenancyPeriodReleased} was
 * already published from both paths. The lesson I'd keep: <b>a known-wrong line waiting on somebody
 * else's work should be re-checked against their code, not against my memory of the conversation.</b>
 */
@Component
public class UnitBoardQuery {

    /** {@code currentTenancyId} and {@code nextTenancyId} are null when there is nobody. */
    public record Row(UUID unitId, UUID propertyId, String name, BigDecimal baseRent,
                      String marketState, UUID currentTenancyId, UUID nextTenancyId) {
    }

    private final JdbcTemplate jdbc;

    public UnitBoardQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> forProperty(UUID workspaceId, UUID propertyId, LocalDate asOf) {
        return jdbc.query("""
            select u.unit_id, u.property_id, u.name, u.base_rent, u.market_state,
              (select p.tenancy_id from reporting_unit_period p
                 where p.workspace_id = u.workspace_id and p.unit_id = u.unit_id
                   and not p.annulled and (not p.released or p.ended_on is not null)
                   and p.starts_on <= ? and (p.ends_on is null or p.ends_on > ?)
                 order by p.starts_on limit 1) as current_tenancy_id,
              (select p.tenancy_id from reporting_unit_period p
                 where p.workspace_id = u.workspace_id and p.unit_id = u.unit_id
                   and not p.annulled and (not p.released or p.ended_on is not null)
                   and p.starts_on > ?
                 order by p.starts_on limit 1) as next_tenancy_id
            from reporting_unit_state u
            where u.workspace_id = ? and u.property_id = ? and not u.removed
            order by u.name
            """,
            (rs, i) -> new Row(
                UUID.fromString(rs.getString("unit_id")),
                UUID.fromString(rs.getString("property_id")),
                rs.getString("name"),
                rs.getBigDecimal("base_rent"),
                rs.getString("market_state"),
                uuidOrNull(rs.getString("current_tenancy_id")),
                uuidOrNull(rs.getString("next_tenancy_id"))),
            asOf, asOf, asOf, workspaceId, propertyId);
    }

    private static UUID uuidOrNull(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
