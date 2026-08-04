package pl.najem.acc;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import pl.najem.eventstore.EventStore;
import pl.najem.eventstore.EventTypeRegistry;

import pl.najem.contracts.events.IntegrationEvent;
import pl.najem.eventstore.StreamEvents;

import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Accounting must be able to stand up on its own beans.
 *
 * <p>No module in this codebase started a Spring context in its own tests, so every module's wiring
 * was exercised only after assembly with all the others. Both of accounting's wiring failures were
 * found by the e2e suite — three layers away, in a suite this module does not own — and the second
 * would never have failed at all: it resolved only because another module happened to publish the
 * bean it needed, and would have broken the day accounting was loaded without it.
 *
 * <p>This test fails where the bean is wired, which is the only place the problem is visible. It
 * deliberately provides <em>only</em> what accounting legitimately depends on — the event store, a
 * data source, a clock, and the bank port — so anything else it demands is a dependency on someone
 * else.
 *
 * <p>The clock is on that list because the platform publishes it from the composition root. It was
 * not always: accounting once started only because propertymanagement happened to publish a Clock,
 * which is exactly the accident this test exists to catch. Moving the bean to PlatformConfig made
 * the dependency legitimate — a module may expect the platform's beans, never a sibling's.
 */
class AccountingContextTest {

    /** Exactly the collaborators accounting is entitled to expect, and nothing more. */
    @Configuration
    @ComponentScan("pl.najem.acc")
    static class OnlyAccounting {

        /**
         * Never queried — this test starts the context, it does not exercise it. The data source is
         * a driver-less stand-in for the same reason: wiring is the subject, not SQL.
         */
        @Bean
        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(new AbstractDataSource() {
                @Override
                public Connection getConnection() {
                    throw new UnsupportedOperationException("wiring test: no database is opened");
                }

                @Override
                public Connection getConnection(String username, String password) {
                    return getConnection();
                }
            });
        }

        @Bean
        EventStore eventStore() {
            return new EventStore() {
                @Override
                public void append(UUID streamId, String streamType, long expectedVersion,
                                   List<Object> events, List<IntegrationEvent> integrationEvents) {
                }

                @Override
                public StreamEvents load(UUID streamId, String streamType) {
                    return new StreamEvents(0, List.of());
                }
            };
        }

        @Bean
        EventTypeRegistry eventTypeRegistry() {
            return new EventTypeRegistry();
        }

        /** The platform's clock, as PlatformConfig publishes it. Fixed here: nothing reads it. */
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        /** Resolves the @Value properties accounting's own adapters declare. */
        @Bean
        static PropertySourcesPlaceholderConfigurer properties() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    /**
     * A module that needs a bean nobody in its own module publishes fails here, immediately, rather
     * than in an integration suite owned by somebody else.
     */
    @Test
    void accountingStartsOnItsOwnBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(OnlyAccounting.class);
            context.getEnvironment().getSystemProperties()
                .put("najem.bank.base-url", "http://localhost:0");
            context.getEnvironment().getSystemProperties().put("najem.bank.iban", "PL00");

            context.refresh();

            assertThat(context.isActive()).isTrue();
        }
    }
}
