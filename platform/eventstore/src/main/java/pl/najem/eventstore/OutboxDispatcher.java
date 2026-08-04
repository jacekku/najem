package pl.najem.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate transactions;
    private final Map<Class<?>, IntegrationEventHandler<?>> handlersByType = new HashMap<>();

    public OutboxDispatcher(JdbcTemplate jdbc, ObjectMapper mapper, EventTypeRegistry registry,
                            PlatformTransactionManager transactionManager,
                            List<IntegrationEventHandler<?>> handlers) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.registry = registry;
        this.transactions = new TransactionTemplate(transactionManager);
        for (IntegrationEventHandler<?> handler : handlers) {
            handlersByType.put(handler.eventType(), handler);
        }
    }

    /** Bounds one tick's work: the whole backlog used to be read into memory at once. */
    private static final int BATCH = 100;

    @Scheduled(fixedDelay = 500)
    public void dispatchPending() {
        var ids = jdbc.queryForList("""
            select id from outbox
            where published_at is null and failed_at is null
            order by id limit ?
            """, Long.class, BATCH);
        for (Long id : ids) {
            dispatch(id);
        }
    }

    /**
     * Delivers one row in its own transaction, so a handler's writes and the row being marked
     * published commit or roll back together. Previously they were two commits: a crash between
     * them redelivered an event whose effects had already landed, which for a rent charge means
     * a second month's obligation against a live tenancy that nothing can tell from a real one.
     * <p>
     * The row is re-read {@code for update skip locked} inside the transaction, so a second
     * application instance skips what this one holds rather than delivering it twice.
     */
    private void dispatch(long id) {
        try {
            transactions.executeWithoutResult(status -> {
                var rows = jdbc.query("""
                    select event_type, payload from outbox
                    where id = ? and published_at is null and failed_at is null
                    for update skip locked
                    """, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, id);
                if (rows.isEmpty()) {
                    return;                     // another instance holds it, or it is already done
                }
                IntegrationEvent event = (IntegrationEvent) read(rows.getFirst()[0], rows.getFirst()[1]);
                IntegrationEventHandler<?> handler = handlersByType.get(event.getClass());
                if (handler != null) {
                    invoke(handler, event);
                }
                jdbc.update("update outbox set published_at = now() where id = ?", id);
            });
        } catch (RuntimeException failure) {
            recordFailure(id, failure);
        }
    }

    /**
     * A failing handler is recorded as data and taken out of the queue. It used to be left
     * pending, so the next tick re-read it first and threw again -- every 500ms, forever, with
     * every event behind it undelivered and nothing anywhere saying so.
     * <p>
     * This runs in its own transaction: the delivery transaction has already rolled back, taking
     * the handler's partial writes with it, and the failure record must survive that rollback.
     */
    private void recordFailure(long id, RuntimeException failure) {
        transactions.executeWithoutResult(status -> jdbc.update("""
            update outbox set failed_at = now(), failure_reason = ? where id = ?
            """, failure.getClass().getSimpleName() + ": " + failure.getMessage(), id));
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
