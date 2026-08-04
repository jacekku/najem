package pl.najem.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import pl.najem.contracts.events.IntegrationEventHandler;
import pl.najem.contracts.events.MoveOutProtocolRecordedEvent;
import pl.najem.contracts.events.RentChangeAppliedEvent;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.contracts.events.TenancyEndedEvent;
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.eventstore.OutboxDispatcher;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

@Configuration
public class PlatformConfig {

    /**
     * Every date in this system is a legal date in one jurisdiction: a rent due on the 1st, a
     * statutory deposit deadline, a retention hold released on the 30th. A LocalDate here means a
     * day in Warsaw and nothing else, so the zone is named rather than inherited.
     *
     * <p>systemDefaultZone() would take it from whatever TZ the container happened to start with,
     * and nothing in this application's configuration sets one. A JVM defaulting to UTC is one to
     * two hours behind Europe/Warsaw, so between midnight and 01:00 or 02:00 the application
     * computes yesterday -- for part of the year and not the rest, which is the worst version of
     * it. A charge falls in the wrong month on some nights only.
     *
     * <p>Services take a Clock rather than calling now() so tests can drive the date. Nothing
     * supplied one at the composition root, so every module's convenience constructor fell back
     * to a wall clock and the application started -- which is why no test saw it: modules test
     * their services directly and never start the context that would have failed.
     */
    @Bean
    Clock clock() {
        return Clock.system(WARSAW);
    }

    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Bean
    EventTypeRegistry eventTypeRegistry() {
        EventTypeRegistry registry = new EventTypeRegistry();
        registry.register(TenancyActivatedEvent.class);
        registry.register(TenancyEndedEvent.class);
        registry.register(RentChangeAppliedEvent.class);
        registry.register(MoveOutProtocolRecordedEvent.class);
        registry.register(WorkspaceCreatedEvent.class);
        return registry;
    }

    @Bean
    EventStore eventStore(JdbcTemplate jdbc, ObjectMapper mapper, EventTypeRegistry registry) {
        return new JdbcEventStore(jdbc, mapper, registry);
    }

    @Bean
    OutboxDispatcher outboxDispatcher(JdbcTemplate jdbc, ObjectMapper mapper, EventTypeRegistry registry,
                                      PlatformTransactionManager transactionManager,
                                      List<IntegrationEventHandler<?>> handlers) {
        return new OutboxDispatcher(jdbc, mapper, registry, transactionManager, handlers);
    }
}
