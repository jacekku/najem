package pl.najem.pm.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TenancyServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static TenancyService service;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        registry.register(TenancyActivatedEvent.class);
        service = new TenancyService(
            new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry));
    }

    @Test
    void activationWritesIntegrationEventToOutbox() {
        var unitId = java.util.UUID.randomUUID();
        var tenancyId = service.reserve(unitId, LocalDate.of(2026, 9, 1),
            new BigDecimal("2500"), "NAJEM/M1/2026");

        service.activate(tenancyId, LocalDate.of(2026, 9, 1));

        var outboxTypes = jdbc.queryForList("select event_type from outbox", String.class);
        assertThat(outboxTypes).containsExactly("TenancyActivatedEvent");
        String payload = jdbc.queryForObject("select payload::text from outbox limit 1", String.class);
        assertThat(payload).contains(tenancyId.toString()).contains("NAJEM/M1/2026").contains("2500");
    }
}
