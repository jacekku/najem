package pl.najem.reporting.application;

import com.fasterxml.jackson.annotation.JsonProperty;
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

        /** Annotated because a record serialises its components only, and a caller wants the total. */
        @JsonProperty("total")
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
        // One query, not one per unit. A board asks this for every property on the screen, so a
        // per-unit occupancy check multiplies into hundreds of round trips on the page a manager
        // hits most. The correlated `exists` uses reporting_unit_period_by_unit.
        var occupancy = jdbc.query("""
            select u.market_state,
                   exists (select 1 from reporting_unit_period p
                             where p.workspace_id = u.workspace_id and p.unit_id = u.unit_id
                               and not p.annulled and (not p.released or p.ended_on is not null)
                               and p.starts_on <= ? and (p.ends_on is null or p.ends_on > ?)) as occupied
            from reporting_unit_state u
            where u.workspace_id = ? and u.property_id = ? and not u.removed
            """,
            (rs, i) -> new Object[]{rs.getString("market_state"), rs.getBoolean("occupied")},
            asOf, asOf, workspaceId, propertyId);

        var tally = new Tally();
        for (var unit : occupancy) {
            tally.add((String) unit[0], (Boolean) unit[1]);
        }
        return tally.counts();
    }

    /**
     * The one place a unit is classified into a bucket, shared with {@link PropertyBoardQuery}.
     * <p>
     * Extracted rather than copied because @najem-pm's seq 357 diagnosis of {@code UnitBoardQuery}
     * is exactly what copying it produces: several queries over one table, drifting into several
     * definitions of the same question, with the newest copy the one that forgets a case. The board
     * needs these counts for every property at once and this class computes them for one, so the
     * SQL genuinely differs — <b>the classification must not.</b>
     */
    static final class Tally {

        private int occupied;
        private int available;
        private int unavailable;
        private int inventory;

        void add(String marketState, boolean isOccupied) {
            if (isOccupied) {
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

        Counts counts() {
            return new Counts(occupied, available, unavailable, inventory);
        }
    }

    /** Every property in a workspace, for a board that lists them. */
    public List<UUID> propertiesIn(UUID workspaceId) {
        return jdbc.queryForList(
            "select property_id from reporting_property where workspace_id = ? order by address",
            UUID.class, workspaceId);
    }
}
