package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import pl.najem.reporting.adapter.persistence.PostgresEventFeed;
import pl.najem.reporting.adapter.persistence.PostgresReporting;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stalled projector must be visible as a number, not inferred from a screen looking wrong.
 * najem-pm made this the condition for accepting an eventually consistent board (seq 110).
 */
@Testcontainers
@Tag("integration")
class ProjectionStatusTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private static ObjectMapper productionMapper() {
        return JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }

    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static EventFeed feed;
    static ProjectionStatus status;

    /** Applies nothing; it exists so the runner has a projection whose lag can be observed. */
    static class NoopProjection implements Projection {
        @Override
        public String name() {
            return "noop";
        }

        @Override
        public Set<String> handles() {
            return Set.of("TenancyReserved");
        }

        @Override
        public void apply(FeedEntry entry) {
        }

        @Override
        public void reset() {
        }
    }

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/reporting").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        feed = new PostgresEventFeed(jdbc, productionMapper());
        status = new ProjectionStatus(jdbc);
    }

    @BeforeEach
    void clear() {
        jdbc.update("delete from events");
        jdbc.update("delete from reporting_checkpoint");
    }

    private static void append(String streamType, String eventType) {
        jdbc.update("""
            insert into events(stream_id, stream_type, version, event_type, payload)
            values (?,?,?,?,cast('{}' as jsonb))
            """, UUID.randomUUID(), streamType, 0L, eventType);
    }

    private static ProjectionRunner runner() {
        return PostgresReporting.runner(jdbc, productionMapper(), tx, List.of(new NoopProjection()), 100);
    }

    @Test
    void reportsAProjectionAsCaughtUpOnceItHasDrained() {
        append("Tenancy", "TenancyReserved");
        runner().runOnce();

        assertThat(status.all()).singleElement()
            .satisfies(s -> {
                assertThat(s.projection()).isEqualTo("noop");
                assertThat(s.eventsBehind()).isZero();
                assertThat(s.caughtUp()).isTrue();
            });
    }

    /** The whole point: a projector that has stopped shows a growing number rather than nothing. */
    @Test
    void reportsHowFarBehindAStalledProjectionIs() {
        append("Tenancy", "TenancyReserved");
        runner().runOnce();

        append("Tenancy", "TenancyReserved");
        append("Unit", "UnitOpenedToRent");

        assertThat(status.all()).singleElement()
            .satisfies(s -> {
                assertThat(s.eventsBehind()).isEqualTo(2);
                assertThat(s.caughtUp()).isFalse();
            });
    }

    /**
     * Events on streams Reporting may not read are not lag. Counting them would report a healthy
     * projector as permanently behind, and a lag number nobody can ever get to zero is one people
     * learn to ignore.
     */
    @Test
    void doesNotCountEventsItIsNotAllowedToSeeAsLag() {
        append("Tenancy", "TenancyReserved");
        runner().runOnce();

        append("Invitation", "MemberInvited");
        append("Invitation", "MemberInvited");

        assertThat(status.all()).singleElement()
            .satisfies(s -> assertThat(s.eventsBehind())
                .as("forbidden streams are invisible, so they cannot be outstanding work")
                .isZero());
    }

    @Test
    void reportsNothingBeforeAnyProjectionHasEverRun() {
        append("Tenancy", "TenancyReserved");

        assertThat(status.all()).isEmpty();
    }
}
