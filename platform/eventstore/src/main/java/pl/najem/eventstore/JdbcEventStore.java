package pl.najem.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import pl.najem.contracts.events.IntegrationEvent;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class JdbcEventStore implements EventStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EventTypeRegistry registry;

    public JdbcEventStore(JdbcTemplate jdbc, ObjectMapper mapper, EventTypeRegistry registry) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.registry = registry;
    }

    @Override
    public void append(UUID streamId, String streamType, long expectedVersion,
                       List<Object> events, List<IntegrationEvent> integrationEvents) {
        long version = expectedVersion;
        try {
            for (Object event : events) {
                jdbc.update(
                    "insert into events(stream_id, stream_type, version, event_type, payload) values (?,?,?,?,?::jsonb)",
                    streamId, streamType, ++version, registry.nameOf(event.getClass()), write(event));
            }
        } catch (DuplicateKeyException ex) {
            throw new ConcurrencyException();
        }
        for (IntegrationEvent event : integrationEvents) {
            jdbc.update("insert into outbox(event_type, payload) values (?, ?::jsonb)",
                registry.nameOf(event.getClass()), write(event));
        }
    }

    @Override
    public StreamEvents load(UUID streamId) {
        List<Map.Entry<Long, Object>> rows = jdbc.query(
            "select version, event_type, payload from events where stream_id = ? order by version",
            (rs, i) -> Map.entry(rs.getLong(1), read(rs.getString(2), rs.getString(3))),
            streamId);
        long version = rows.isEmpty() ? 0 : rows.getLast().getKey();
        return new StreamEvents(version, rows.stream().map(Map.Entry::getValue).toList());
    }

    private String write(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Object read(String type, String json) {
        try {
            return mapper.readValue(json, registry.resolve(type));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
