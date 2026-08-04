package pl.najem.reporting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Every property in a workspace with its occupancy already counted — the prototype's entry point,
 * and the only endpoint that yields the {@code propertyId} every other reporting call requires.
 * <p>
 * <b>One query for the whole screen, not one per property.</b> {@link PropertyOccupancy#countsFor}
 * answers this for a single property, and calling it in a loop is the N+1 that
 * {@link UnitBoardQuery} exists to avoid one level down — a portfolio list is precisely the page
 * where that multiplies. @najem-reviewer's seq 358 point is why it is the entry point in a
 * mechanical sense rather than a narrative one: {@code /api/reporting/units} requires a
 * {@code propertyId}, and nothing else produces one.
 * <p>
 * The classification lives in {@link PropertyOccupancy.Tally} and is shared rather than copied.
 * Two queries answering "how many units are let" in two places is how {@code UnitBoardQuery} came
 * to disagree with {@code UnitOccupancy} about what counts (@najem-pm, seq 357), and the copy is
 * always the one that forgets a case.
 */
@Component
public class PropertyBoardQuery {

    /**
     * A property with its counts. Address is included because a screen listing properties has
     * nothing else to name them by — and it is a fact about a building, held by PM already, not
     * personal data: no owner or tenant identity appears here.
     */
    public record Row(UUID propertyId, String address, PropertyOccupancy.Counts occupancy) {
    }

    private final JdbcTemplate jdbc;

    public PropertyBoardQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <b>{@code asOf} applies to occupancy only; market state is always current</b> — the same
     * documented limit as {@link PropertyOccupancy#countsFor}, and for the same reason: PM emits
     * open and close events but this projection keeps only the latest, so there is no state
     * timeline to ask a past question of.
     * <p>
     * A property with no units yet appears with zero counts rather than being omitted. It is in the
     * portfolio, a manager put it there, and a list that silently drops it looks like the write
     * failed — which is the read-your-writes trap @najem-coordinator hit at seq 303, in the one
     * place it would be met first.
     */
    public List<Row> forWorkspace(UUID workspaceId, LocalDate asOf) {
        var tallies = new LinkedHashMap<UUID, PropertyOccupancy.Tally>();
        var addresses = new LinkedHashMap<UUID, String>();

        jdbc.query("""
            select pr.property_id, pr.address, u.market_state,
                   exists (select 1 from reporting_unit_period p
                             where p.workspace_id = u.workspace_id and p.unit_id = u.unit_id
                               and not p.annulled and (not p.released or p.ended_on is not null)
                               and p.starts_on <= ? and (p.ends_on is null or p.ends_on > ?)) as occupied
            from reporting_property pr
            left join reporting_unit_state u
              on u.workspace_id = pr.workspace_id and u.property_id = pr.property_id and not u.removed
            where pr.workspace_id = ?
            order by pr.address, u.name
            """,
            rs -> {
                var propertyId = UUID.fromString(rs.getString("property_id"));
                addresses.putIfAbsent(propertyId, rs.getString("address"));
                var tally = tallies.computeIfAbsent(propertyId, id -> new PropertyOccupancy.Tally());
                // The left join yields one all-null unit row for a property with no units. It is a
                // property with nothing in it, not a unit in an unknown state, so it is counted as
                // neither rather than falling into the available bucket by default.
                var marketState = rs.getString("market_state");
                if (marketState != null) {
                    tally.add(marketState, rs.getBoolean("occupied"));
                }
            },
            asOf, asOf, workspaceId);

        var rows = new ArrayList<Row>(tallies.size());
        tallies.forEach((propertyId, tally) ->
            rows.add(new Row(propertyId, addresses.get(propertyId), tally.counts())));
        return rows;
    }
}
