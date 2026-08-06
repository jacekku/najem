package pl.najem.reporting.adapter.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pl.najem.reporting.application.EventFeed;
import pl.najem.reporting.application.FeedEntry;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * {@link EventFeed} over the shared {@code events} table.
 * <p>
 * Reads {@code events} rows directly and MUST NOT be refactored to go through
 * {@code EventStore.load} — see the port for why that would widen the allowlist.
 * <p>
 * The allowlist is applied in SQL rather than by filtering the result in Java, and the difference
 * is not cosmetic: {@code limit} is applied after the {@code where}, so a run of forbidden events
 * cannot consume the batch window. Filtering afterwards would let a busy private stream return an
 * all-forbidden batch, which the runner would read as progress of zero handled events forever.
 */
@Component
public class PostgresEventFeed implements EventFeed {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PostgresEventFeed(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<FeedEntry> since(long afterGlobalSeq, int limit) {
        var args = new Object[ALLOWED_STREAMS.size() + 2];
        args[0] = afterGlobalSeq;
        int i = 1;
        for (var stream : ALLOWED_STREAMS) {
            args[i++] = stream;
        }
        args[i] = limit;
        return jdbc.query("""
            select global_seq, stream_id, stream_type, event_type, occurred_at, payload::text as payload
            from events
            where global_seq > ? and stream_type in (%s)
            order by global_seq
            limit ?
            """.formatted(String.join(",", Collections.nCopies(ALLOWED_STREAMS.size(), "?"))),
            (rs, rowNum) -> new FeedEntry(
                rs.getLong("global_seq"),
                UUID.fromString(rs.getString("stream_id")),
                rs.getString("stream_type"),
                rs.getString("event_type"),
                rs.getTimestamp("occurred_at").toInstant(),
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
