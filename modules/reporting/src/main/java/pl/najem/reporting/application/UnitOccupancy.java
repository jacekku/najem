package pl.najem.reporting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A unit's occupancy read from its calendar.
 * <p>
 * Vacancy is <em>derived here, at read time, from the absence of a period</em> — never stored and
 * never projected from an event, because nothing emits "the unit went empty". Storing it would mean
 * writing rows for something that never happened, and they would go stale the moment a
 * back-dated reservation filled the gap.
 */
@Component
public class UnitOccupancy {

    /** {@code to} is exclusive and null means "still running". Half-open, matching PM's calendar. */
    public record Span(LocalDate from, LocalDate to, String state, UUID tenancyId) {

        public long days(LocalDate asOf) {
            return ChronoUnit.DAYS.between(from, to == null ? asOf : to);
        }
    }

    private final JdbcTemplate jdbc;

    public UnitOccupancy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Occupied and vacant spans in order. Released periods are excluded: a reservation that was
     * cancelled never occupied the unit, so counting it would overstate occupancy — it stays
     * visible on the unit's timeline, which is where the fact that it happened belongs.
     */
    public List<Span> spansFor(UUID workspaceId, UUID unitId, LocalDate asOf) {
        var periods = jdbc.query("""
            select tenancy_id, starts_on, ends_on from reporting_unit_period
            where workspace_id = ? and unit_id = ? and not annulled and (not released or ended_on is not null)
            order by starts_on
            """,
            (rs, i) -> new Span(rs.getDate("starts_on").toLocalDate(),
                rs.getDate("ends_on") == null ? null : rs.getDate("ends_on").toLocalDate(),
                "occupied", UUID.fromString(rs.getString("tenancy_id"))),
            workspaceId, unitId);

        var spans = new ArrayList<Span>();
        LocalDate previousEnd = null;
        for (var period : periods) {
            if (previousEnd != null && previousEnd.isBefore(period.from())) {
                spans.add(new Span(previousEnd, period.from(), "vacant", null));
            }
            spans.add(period);
            if (period.to() == null) {
                // Indefinite: occupies the unit forever until ended, so nothing can follow it.
                return spans;
            }
            previousEnd = period.to();
        }
        if (previousEnd != null && previousEnd.isBefore(asOf)) {
            spans.add(new Span(previousEnd, null, "vacant", null));
        }
        return spans;
    }

    /** The tenancy occupying the unit on a date, if any. */
    public UUID occupantOn(UUID workspaceId, UUID unitId, LocalDate date) {
        var found = jdbc.queryForList("""
            select tenancy_id from reporting_unit_period
            where workspace_id = ? and unit_id = ? and not annulled and (not released or ended_on is not null)
              and starts_on <= ? and (ends_on is null or ends_on > ?)
            order by starts_on limit 1
            """, UUID.class, workspaceId, unitId, date, date);
        return found.isEmpty() ? null : found.get(0);
    }

    /** The next tenancy due to start after a date — what a Unit Board shows as "upcoming". */
    public UUID nextOccupantAfter(UUID workspaceId, UUID unitId, LocalDate date) {
        var found = jdbc.queryForList("""
            select tenancy_id from reporting_unit_period
            where workspace_id = ? and unit_id = ? and not annulled and (not released or ended_on is not null) and starts_on > ?
            order by starts_on limit 1
            """, UUID.class, workspaceId, unitId, date);
        return found.isEmpty() ? null : found.get(0);
    }
}
