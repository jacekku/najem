package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The flagship read model: one tenancy's story, in the order it happened.
 * <p>
 * The domain expert's own example is the specification — "checklist done 5 days before keys handed
 * over, moved in, deposit paid on date A, rent paid on date B, invoice generated but unpaid (shows
 * red)". So this is a curated narrative, not an audit log: unrecognised event types are skipped,
 * and the event store already serves anyone who needs everything.
 */
@Component
public class TenancyTimelineProjection implements Projection {

    public static final String NAME = "tenancy-timeline";

    private static final Set<String> HANDLES = Set.of(
        "TenancyReserved", "TenancyActivated", "TenancyReservationCancelled",
        "ChecklistItemCompleted", "HandoverProtocolRecorded", "RentChangeApplied",
        "ChargePosted", "ChargeDeactivated", "CreditNoteIssued", "PaymentIngested", "PaymentAllocated");

    private final JdbcTemplate jdbc;

    public TenancyTimelineProjection(JdbcTemplate jdbc) {
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
        jdbc.update("delete from reporting_timeline_entry where level = 'tenancy'");
        jdbc.update("delete from reporting_charge_index");
        jdbc.update("delete from reporting_payment_index");
        jdbc.update("delete from reporting_tenancy_index");
    }

    @Override
    public void apply(FeedEntry entry) {
        var p = entry.payload();
        switch (entry.eventType()) {
            case "TenancyReserved" -> {
                var tenancyId = uuid(p, "tenancyId");
                jdbc.update("""
                    insert into reporting_tenancy_index(tenancy_id, workspace_id, unit_id) values (?,?,?)
                    on conflict (tenancy_id) do nothing
                    """, tenancyId, uuid(p, "workspaceId"), uuid(p, "unitId"));
                // Dated when the agreement was recorded, not when the tenancy starts: a manager
                // reading the story wants "we signed this in August for a September start", and
                // the start date is in the summary where it belongs.
                record(entry, tenancyId, occurredOn(entry), "tenancy-reserved",
                    "Reserved from " + text(p, "startDate") + " (" + text(p, "legalForm") + ")");
            }
            case "TenancyActivated" -> record(entry, uuid(p, "tenancyId"), date(p, "activatedOn"),
                "tenancy-activated", "Tenancy started");
            case "TenancyReservationCancelled" -> record(entry, uuid(p, "tenancyId"), occurredOn(entry),
                "reservation-cancelled", "Reservation cancelled: " + text(p, "reason"));
            case "ChecklistItemCompleted" -> record(entry, uuid(p, "tenancyId"), occurredOn(entry),
                "checklist-item-completed", "Checklist: " + text(p, "key"));
            case "HandoverProtocolRecorded" -> {
                var protocol = p.path("protocol");
                record(entry, uuid(p, "tenancyId"), dateOr(protocol, "date", occurredOn(entry)),
                    "handover-recorded", "Handover protocol recorded (" + protocol.path("type").asText("") + ")");
            }
            case "RentChangeApplied" -> record(entry, uuid(p, "tenancyId"), date(p, "effectiveFrom"),
                "rent-changed", "Rent changed to " + p.path("monthly").path("total").asText("?")
                    + " (" + text(p, "type") + ")");
            case "ChargePosted" -> {
                var tenancyId = uuid(p, "tenancyId");
                jdbc.update("""
                    insert into reporting_charge_index(charge_id, tenancy_id) values (?,?)
                    on conflict (charge_id) do nothing
                    """, uuid(p, "chargeId"), tenancyId);
                record(entry, tenancyId, date(p, "dueDate"), "charge-posted",
                    text(p, "component") + " " + text(p, "amount") + " due " + text(p, "dueDate"));
            }
            case "ChargeDeactivated" -> record(entry, uuid(p, "tenancyId"), occurredOn(entry),
                "charge-deactivated", "Charge withdrawn: " + text(p, "reason"));
            case "CreditNoteIssued" -> record(entry, uuid(p, "tenancyId"), date(p, "issuedOn"),
                "credit-note-issued", "Credit note " + text(p, "amount") + ": " + text(p, "reason"));
            case "PaymentIngested" -> jdbc.update("""
                insert into reporting_payment_index(payment_id, booking_date) values (?,?)
                on conflict (payment_id) do nothing
                """, uuid(p, "paymentId"), date(p, "bookingDate"));
            case "PaymentAllocated" -> {
                // The only event on the list that names no tenancy: it lives on the Payment stream
                // and knows a charge. Unknown charge means the ChargePosted has not been projected
                // yet or belongs to a stream we cannot read -- record nothing rather than guess.
                var tenancyId = tenancyOfCharge(uuid(p, "chargeId"));
                if (tenancyId != null) {
                    record(entry, tenancyId, bookingDateOf(uuid(p, "paymentId"), occurredOn(entry)),
                        "payment-allocated", "Payment allocated: " + text(p, "amount"));
                }
            }
            default -> {
                // Unreachable while handles() and this switch agree; skipping is the curated-timeline
                // rule (Decision 4) rather than an error.
            }
        }
    }

    /** When the money actually arrived. Falls back to the allocation's own time if unknown. */
    private LocalDate bookingDateOf(UUID paymentId, LocalDate fallback) {
        var found = jdbc.queryForList(
            "select booking_date from reporting_payment_index where payment_id = ?",
            LocalDate.class, paymentId);
        return found.isEmpty() ? fallback : found.get(0);
    }

    private UUID tenancyOfCharge(UUID chargeId) {
        var found = jdbc.queryForList(
            "select tenancy_id from reporting_charge_index where charge_id = ?", UUID.class, chargeId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Writes one entry. The workspace comes from the tenancy index rather than the payload, because
     * accounting's events do not carry one — see V61. An entry whose tenancy is unknown is dropped:
     * a timeline row with a guessed workspace would cross the tenancy boundary silently.
     */
    private void record(FeedEntry entry, UUID tenancyId, LocalDate occurredOn, String kind, String summary) {
        var workspace = jdbc.queryForList(
            "select workspace_id from reporting_tenancy_index where tenancy_id = ?", UUID.class, tenancyId);
        if (workspace.isEmpty()) {
            return;
        }
        jdbc.update("""
            insert into reporting_timeline_entry(workspace_id, level, subject_id, occurred_on,
                                                 global_seq, kind, summary, detail)
            values (?, 'tenancy', ?, ?, ?, ?, ?, cast(? as jsonb))
            on conflict (level, subject_id, global_seq) do nothing
            """,
            workspace.get(0), tenancyId, occurredOn, entry.globalSeq(), kind, summary, entry.payload().toString());
    }

    private static LocalDate occurredOn(FeedEntry entry) {
        return LocalDate.ofInstant(entry.occurredAt(), java.time.ZoneOffset.UTC);
    }

    private static LocalDate date(JsonNode payload, String field) {
        return LocalDate.parse(payload.get(field).asText());
    }

    private static LocalDate dateOr(JsonNode payload, String field, LocalDate fallback) {
        var value = payload.get(field);
        return value == null || value.isNull() ? fallback : LocalDate.parse(value.asText());
    }

    private static String text(JsonNode payload, String field) {
        return payload.path(field).asText("");
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.get(field).asText());
    }

    /** Every event type this projection renders, for the tripwire and for anyone auditing coverage. */
    public static List<String> renderedEventTypes() {
        return HANDLES.stream().sorted().toList();
    }
}
