package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * A unit's history and its current state: "open X days → reserved → tenant Y months → repairs,
 * problems".
 * <p>
 * Everything here comes from the {@code Unit} stream, including the tenancy calendar — PM registers
 * each tenancy period on the unit itself to enforce the no-overlap invariant, so the unit already
 * knows who occupies it and when. No cross-stream join is needed and none is done.
 */
@Component
public class UnitTimelineProjection implements Projection {

    public static final String NAME = "unit-timeline";

    private static final Set<String> HANDLES = Set.of(
        "UnitAddedToProperty", "UnitBaseRentSet", "UnitDetailsUpdated",
        "UnitOpenedToRent", "UnitClosedToRent", "UnitRemovedFromProperty",
        "TenancyPeriodRegistered", "TenancyPeriodReleased", "TenancyEnded");

    private final JdbcTemplate jdbc;

    public UnitTimelineProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> handles() {
        return HANDLES;
    }

    @Override
    public void reset() {
        jdbc.update("delete from reporting_timeline_entry where level = 'unit'");
        jdbc.update("delete from reporting_unit_period");
        jdbc.update("delete from reporting_unit_state");
    }

    @Override
    public void apply(FeedEntry entry) {
        var p = entry.payload();
        // TenancyEnded rides the Tenancy stream and names no unit — the unit is whichever one this
        // tenancy's period sits on, which this projection already recorded.
        if ("TenancyEnded".equals(entry.eventType())) {
            applyTenancyEnded(entry, p);
            return;
        }
        var unitId = uuid(p, "unitId");
        switch (entry.eventType()) {
            case "UnitAddedToProperty" -> {
                jdbc.update("""
                    insert into reporting_unit_state(unit_id, workspace_id, property_id, name, base_rent)
                    values (?,?,?,?,?)
                    on conflict (unit_id) do nothing
                    """, unitId, uuid(p, "workspaceId"), uuid(p, "propertyId"),
                    text(p, "name"), decimal(p, "baseRent"));
                record(entry, unitId, "unit-added", "Added to property as " + text(p, "name"));
            }
            case "UnitBaseRentSet" -> {
                jdbc.update("update reporting_unit_state set base_rent = ? where unit_id = ?",
                    decimal(p, "baseRent"), unitId);
                record(entry, unitId, "base-rent-set", "Anchor rent set to " + text(p, "baseRent"));
            }
            case "UnitDetailsUpdated" -> record(entry, unitId, "details-updated", "Details updated");
            case "UnitOpenedToRent" -> {
                jdbc.update("update reporting_unit_state set market_state = 'open' where unit_id = ?", unitId);
                record(entry, unitId, "opened-to-rent", "Opened to rent: " + text(p, "reason"));
            }
            case "UnitClosedToRent" -> {
                jdbc.update("update reporting_unit_state set market_state = 'closed' where unit_id = ?", unitId);
                record(entry, unitId, "closed-to-rent", "Closed to rent: " + text(p, "reason"));
            }
            case "UnitRemovedFromProperty" -> {
                jdbc.update("""
                    update reporting_unit_state set removed = true, market_state = 'removed' where unit_id = ?
                    """, unitId);
                record(entry, unitId, "removed-from-property", "Removed: " + text(p, "reason"));
            }
            case "TenancyPeriodRegistered" -> {
                jdbc.update("""
                    insert into reporting_unit_period(unit_id, tenancy_id, workspace_id, starts_on, ends_on)
                    values (?,?,?,?,?)
                    on conflict (unit_id, tenancy_id) do update
                      set starts_on = excluded.starts_on, ends_on = excluded.ends_on, released = false
                    """, unitId, uuid(p, "tenancyId"), uuid(p, "workspaceId"),
                    date(p, "starts", "start"), dateOrNull(p, "ends", "end"));
                record(entry, unitId, "tenancy-period-registered",
                    "Reserved " + text(p, "start") + " – " + endLabel(p));
            }
            case "TenancyPeriodReleased" -> {
                jdbc.update("""
                    update reporting_unit_period set released = true where unit_id = ? and tenancy_id = ?
                    """, unitId, uuid(p, "tenancyId"));
                // Shown, not deleted. The period really was on this unit's calendar and a manager
                // who saw it there needs to see that it went away — a record that silently vanishes
                // is how people stop trusting a timeline.
                record(entry, unitId, "tenancy-period-released", "Reservation released");
            }
            default -> {
                // Curated timeline (Decision 4): anything unrecognised is skipped, not an error.
            }
        }
    }

    /**
     * An annulled tenancy is excluded from occupancy and kept on the timeline, marked. Decision 5:
     * a mistaken activation genuinely happened to that unit, and records that silently vanish are
     * how people stop trusting a timeline — but it never housed anyone, so counting it as occupancy
     * would report a flat as let for a period nobody lived in it.
     */
    private void applyTenancyEnded(FeedEntry entry, JsonNode p) {
        var tenancyId = uuid(p, "tenancyId");
        var unitIds = jdbc.queryForList(
            "select unit_id from reporting_unit_period where tenancy_id = ?", UUID.class, tenancyId);
        if (unitIds.isEmpty()) {
            // The period was never projected — nothing to annul and nowhere to put the entry.
            return;
        }
        var unitId = unitIds.get(0);
        if (isAnnulment(p)) {
            jdbc.update("update reporting_unit_period set annulled = true where tenancy_id = ?", tenancyId);
            record(entry, unitId, "tenancy-annulled", "Annulled: this tenancy should never have existed");
        } else {
            // Records the ending AND shortens the period to it. PM releases the calendar slot on
            // ending as well as on cancellation, so without this the tenancy's occupancy would be
            // discarded along with the reservation's — the unit would read as never let.
            jdbc.update("""
                update reporting_unit_period set ended_on = ?, ends_on = ? where tenancy_id = ?
                """, date(p, "endDate"), date(p, "endDate"), tenancyId);
            record(entry, unitId, "tenancy-ended", "Tenancy ended " + text(p, "endDate"));
        }
    }

    /**
     * Accepts both spellings on purpose. The stored event carries Jackson's default enum rendering
     * ({@code ERROR_ANNULLED}) while {@code EndReason.wireName()} — the form PM calls the published
     * contract — is {@code error-annulled}, and only the integration event uses it. Matching either
     * means a change on one path cannot silently turn every annulment back into an ending.
     */
    private static boolean isAnnulment(JsonNode p) {
        var reason = p.path("reasonType").asText("");
        return "ERROR_ANNULLED".equals(reason) || "error-annulled".equals(reason);
    }

    /**
     * Every unit event carries its own {@code workspaceId}, so unlike the tenancy timeline this one
     * needs no derivation — PM leads every event with it, as the convention requires.
     */
    private void record(FeedEntry entry, UUID unitId, String kind, String summary) {
        var workspaceId = entry.payload().path("workspaceId");
        if (workspaceId.isMissingNode() || workspaceId.isNull()) {
            return;
        }
        jdbc.update("""
            insert into reporting_timeline_entry(workspace_id, level, subject_id, occurred_on,
                                                 global_seq, kind, summary, detail)
            values (?, 'unit', ?, ?, ?, ?, ?, cast(? as jsonb))
            on conflict (level, subject_id, global_seq) do nothing
            """,
            UUID.fromString(workspaceId.asText()), unitId,
            LocalDate.ofInstant(entry.occurredAt(), java.time.ZoneOffset.UTC),
            entry.globalSeq(), kind, summary, entry.payload().toString());
    }

    private static String endLabel(JsonNode p) {
        var end = p.get("end");
        return end == null || end.isNull() ? "indefinite" : end.asText();
    }

    private static LocalDate date(JsonNode p, String... candidates) {
        for (String field : candidates) {
            var value = p.get(field);
            if (value != null && !value.isNull()) {
                return LocalDate.parse(value.asText());
            }
        }
        throw new IllegalArgumentException("No date field among " + String.join("/", candidates));
    }

    private static LocalDate dateOrNull(JsonNode p, String... candidates) {
        for (String field : candidates) {
            var value = p.get(field);
            if (value != null && !value.isNull()) {
                return LocalDate.parse(value.asText());
            }
        }
        return null;
    }

    private static java.math.BigDecimal decimal(JsonNode p, String field) {
        var value = p.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    private static String text(JsonNode p, String field) {
        return p.path(field).asText("");
    }

    private static UUID uuid(JsonNode p, String field) {
        return UUID.fromString(p.get(field).asText());
    }
}
