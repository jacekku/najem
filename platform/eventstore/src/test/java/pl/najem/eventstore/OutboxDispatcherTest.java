package pl.najem.eventstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contracts.events.IntegrationEventHandler;
import pl.najem.contracts.events.TenancyActivatedEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OutboxDispatcherTest {

    static class RecordingHandler implements IntegrationEventHandler<TenancyActivatedEvent> {
        final List<TenancyActivatedEvent> received = new ArrayList<>();
        boolean poisoned;

        @Override
        public Class<TenancyActivatedEvent> eventType() {
            return TenancyActivatedEvent.class;
        }

        @Override
        public void handle(TenancyActivatedEvent event) {
            if (poisoned) {
                throw new IllegalStateException("handler refuses this event");
            }
            received.add(event);
        }
    }

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static RecordingHandler handler = new RecordingHandler();
    static OutboxDispatcher dispatcher;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/eventstore").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        registry.register(TenancyActivatedEvent.class);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        store = new JdbcEventStore(jdbc, mapper, registry);
        dispatcher = new OutboxDispatcher(jdbc, mapper, registry,
            new DataSourceTransactionManager(dataSource), List.of(handler));
    }

    @Test
    void deliversOutboxEventToHandlerExactlyOnce() {
        var event = new TenancyActivatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            LocalDate.of(2026, 9, 1), new BigDecimal("2500"), false, null, null, null,
            "zwykly", null, "NAJEM/M1/2026");
        store.append(UUID.randomUUID(), "Tenancy", 0, List.of(), List.of(event));

        dispatcher.dispatchPending();

        assertThat(handler.received).containsExactly(event);
        Integer unpublished = jdbc.queryForObject(
            "select count(*) from outbox where published_at is null", Integer.class);
        assertThat(unpublished).isZero();

        dispatcher.dispatchPending();
        assertThat(handler.received).hasSize(1);
    }

    // A throwing handler used to leave its row unmarked, so every subsequent tick re-read it
    // first and threw again -- forever, with everything behind it undelivered. The queue went
    // silent while looking healthy, which is the worst available failure.
    @Test
    void oneFailingEventNeitherRetriesForeverNorBlocksTheRest() {
        handler.received.clear();
        handler.poisoned = true;
        var poison = activation();
        store.append(UUID.randomUUID(), "Tenancy", 0, List.of(), List.of(poison));

        dispatcher.dispatchPending();

        // Recorded as data, with the reason, rather than left pending to be retried blindly.
        var failed = jdbc.queryForMap(
            "select failed_at, failure_reason from outbox where published_at is null");
        assertThat(failed.get("failed_at")).isNotNull();
        assertThat((String) failed.get("failure_reason")).contains("handler refuses this event");

        handler.poisoned = false;
        var good = activation();
        store.append(UUID.randomUUID(), "Tenancy", 0, List.of(), List.of(good));

        dispatcher.dispatchPending();

        // The event queued behind the poison one is delivered, and the poison one is not retried.
        assertThat(handler.received).containsExactly(good);
    }

    private static TenancyActivatedEvent activation() {
        return new TenancyActivatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            LocalDate.of(2026, 9, 1), new BigDecimal("2500"), false, null, null, null,
            "zwykly", null, "NAJEM/M1/2026");
    }
}
