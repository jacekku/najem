package pl.najem.reporting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "How many units rented / available / unavailable" — the property-level answer, computed at read
 * time from the unit calendar rather than stored.
 * <p>
 * Nothing here is a projection of its own: occupancy has no event and no moment. A unit becomes
 * vacant because a period ended, not because anything announced it, and a stored count would be
 * wrong for every date except the one it was computed for.
 */
@Component
public class PropertyOccupancy {

    /**
     * {@code unavailable} covers units closed to rent — under renovation, held back — as distinct
     * from {@code available}, which is on the market and empty. {@code inventory} units have never
     * been put on the market and are counted separately rather than folded into either: a flat
     * nobody has listed is not a letting failure, and merging them would make occupancy read worse
     * than it is for a property still being brought online.
     */
    public record Counts(int occupied, int available, int unavailable, int inventory) {

        public int total() {
            return occupied + available + unavailable + inventory;
        }
    }

    private final JdbcTemplate jdbc;

    public PropertyOccupancy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <b>{@code asOf} applies to OCCUPANCY ONLY. Market state is always current.</b>
     * <p>
     * A unit's open/closed state is not historised — PM emits open and close events, but this
     * projection keeps only the latest, so asking about a past date returns who was living there
     * then alongside whether the flat is lettable <em>now</em>. For today's date, which is what a
     * board asks, the answer is wholly correct; for a past date the occupied count is right and the
     * available/unavailable split reflects today.
     * <p>
     * Stated rather than fixed because historising market state means projecting a state timeline
     * nobody has asked for yet, and a half-documented answer is worse than a documented limit.
     * A test pins this behaviour so it cannot drift into being accidentally relied upon.
     */
    public Counts countsFor(UUID workspaceId, UUID propertyId, LocalDate asOf) {
        var units = jdbc.query("""
            select unit_id, market_state from reporting_unit_state
            where workspace_id = ? and property_id = ? and not removed
            """,
            (rs, i) -> new Object[]{UUID.fromString(rs.getString("unit_id")), rs.getString("market_state")},
            workspaceId, propertyId);

        int occupied = 0;
        int available = 0;
        int unavailable = 0;
        int inventory = 0;
        for (var unit : units) {
            var unitId = (UUID) unit[0];
            var marketState = (String) unit[1];
            if (isOccupiedOn(workspaceId, unitId, asOf)) {
                // Occupancy beats market state: a unit closed for renovation while a tenant is
                // still living there is occupied, whatever the board says about lettability.
                occupied++;
            } else if ("closed".equals(marketState)) {
                unavailable++;
            } else if ("inventory".equals(marketState)) {
                inventory++;
            } else {
                available++;
            }
        }
        return new Counts(occupied, available, unavailable, inventory);
    }

    /** Every property in a workspace, for a board that lists them. */
    public List<UUID> propertiesIn(UUID workspaceId) {
        return jdbc.queryForList(
            "select property_id from reporting_property where workspace_id = ? order by address",
            UUID.class, workspaceId);
    }

    private boolean isOccupiedOn(UUID workspaceId, UUID unitId, LocalDate asOf) {
        Integer count = jdbc.queryForObject("""
            select count(*) from reporting_unit_period
            where workspace_id = ? and unit_id = ? and not annulled and (not released or ended_on is not null)
              and starts_on <= ? and (ends_on is null or ends_on > ?)
            """, Integer.class, workspaceId, unitId, asOf, asOf);
        return count != null && count > 0;
    }
}
