package pl.najem.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import pl.najem.contracts.events.IntegrationEvent;
import pl.najem.contracts.events.IntegrationEventHandler;

import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers outbox rows by invoking handler beans directly (plain Java calls, no broker).
 * Events without a registered handler are marked published and skipped.
 */
public class OutboxDispatcher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EventTypeRegistry registry;
    private final Map<Class<?>, IntegrationEventHandler<?>> handlersByType = new HashMap<>();

    public OutboxDispatcher(JdbcTemplate jdbc, ObjectMapper mapper, EventTypeRegistry registry,
                            List<IntegrationEventHandler<?>> handlers) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.registry = registry;
        for (IntegrationEventHandler<?> handler : handlers) {
            handlersByType.put(handler.eventType(), handler);
        }
    }

    @Scheduled(fixedDelay = 500)
    public void dispatchPending() {
        var rows = jdbc.query("select id, event_type, payload from outbox where published_at is null order by id",
            (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)});
        for (Object[] row : rows) {
            IntegrationEvent event = (IntegrationEvent) read((String) row[1], (String) row[2]);
            IntegrationEventHandler<?> handler = handlersByType.get(event.getClass());
            if (handler != null) {
                invoke(handler, event);
            }
            jdbc.update("update outbox set published_at = now() where id = ?", row[0]);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends IntegrationEvent> void invoke(IntegrationEventHandler<?> handler, IntegrationEvent event) {
        ((IntegrationEventHandler<T>) handler).handle((T) event);
    }

    private Object read(String type, String json) {
        try {
            return mapper.readValue(json, registry.resolve(type));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
