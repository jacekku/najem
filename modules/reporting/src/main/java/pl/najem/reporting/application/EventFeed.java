package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reporting's window onto the shared event store, ordered by {@code global_seq}.
 * <p>
 * The coordinator sanctioned Reporting as a privileged read-side <em>restricted to an allowlist
 * of streams</em> (najem-build seq 65 pt 2, amending an earlier proposal to read the table
 * wholesale). Streams are default-private: everything not named below belongs to its owning
 * module and stays there.
 * <p>
 * The restriction lives here, in the one place events enter Reporting, rather than in each
 * projection — a rule every caller must remember is a rule that will eventually be forgotten,
 * and the thing being protected is other modules' private data.
 */
@Component
public class EventFeed {

    /**
     * Per-owner, as ruled:
     * PM {@code Property/Unit/Tenancy} · Accounting {@code TenancyLedger/Payment} ·
     * Contacts all (PII-free by construction) · UserManagement {@code Workspace/User} only.
     * <p>
     * Adding a stream here is a cross-module decision, not a local one: it makes another
     * module's payloads a published interface. Post on najem-build first.
     */
    public static final Set<String> ALLOWED_STREAMS =
        Set.of("Property", "Unit", "Tenancy", "TenancyLedger", "Payment", "Contact", "Workspace", "User");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EventFeed(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * The next batch of allowlisted events after {@code afterGlobalSeq}, in order.
     * <p>
     * Filtering happens in SQL so that a run of forbidden events cannot eat the batch window and
     * stall the cursor — the caller advances past them without ever seeing them. Gaps in
     * {@code global_seq} (rolled-back transactions) are normal and must never be waited for.
     */
    public List<FeedEntry> since(long afterGlobalSeq, int limit) {
        var args = new Object[ALLOWED_STREAMS.size() + 2];
        args[0] = afterGlobalSeq;
        int i = 1;
        for (var stream : ALLOWED_STREAMS) {
            args[i++] = stream;
        }
        args[i] = limit;
        return jdbc.query("""
            select global_seq, stream_id, stream_type, event_type, payload::text as payload
            from events
            where global_seq > ? and stream_type in (%s)
            order by global_seq
            limit ?
            """.formatted(String.join(",", java.util.Collections.nCopies(ALLOWED_STREAMS.size(), "?"))),
            (rs, rowNum) -> new FeedEntry(
                rs.getLong("global_seq"),
                UUID.fromString(rs.getString("stream_id")),
                rs.getString("stream_type"),
                rs.getString("event_type"),
                tree(rs.getString("payload"))),
            args);
    }

    private JsonNode tree(String payload) {
        try {
            return json.readTree(payload);
        } catch (Exception e) {
            // Unreadable jsonb means the event store itself is corrupt; skipping it would hide that.
            throw new DataRetrievalFailureException("Unreadable event payload: " + payload, e);
        }
    }
}
