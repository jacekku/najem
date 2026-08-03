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
import pl.najem.pm.domain.Owner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TenancyServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static PortfolioService portfolio;
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
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        portfolio = new PortfolioService(store, jdbc);
        service = new TenancyService(store, jdbc);
    }

    @Test
    void activationWritesIntegrationEventToOutbox() {
        var unitId = unitIn(UUID.randomUUID());
        var tenancyId = service.reserve(unitId, LocalDate.of(2026, 9, 1),
            new BigDecimal("2500"), "NAJEM/M1/2026");

        service.activate(tenancyId, LocalDate.of(2026, 9, 1));

        var outboxTypes = jdbc.queryForList("select event_type from outbox", String.class);
        assertThat(outboxTypes).containsExactly("TenancyActivatedEvent");
        String payload = jdbc.queryForObject("select payload::text from outbox limit 1", String.class);
        assertThat(payload).contains(tenancyId.toString()).contains("NAJEM/M1/2026").contains("2500");
    }

    @Test
    void activationCarriesTheWorkspaceOfTheUnitNotAConstant() {
        var workspaceId = UUID.randomUUID();
        var tenancyId = service.reserve(unitIn(workspaceId), LocalDate.of(2026, 10, 1),
            new BigDecimal("3000"), "NAJEM/M2/2026");

        service.activate(tenancyId, LocalDate.of(2026, 10, 1));

        String payload = jdbc.queryForObject(
            "select payload::text from outbox where payload::text like ? limit 1",
            String.class, "%" + tenancyId + "%");
        assertThat(payload).contains(workspaceId.toString());
    }

    private static UUID unitIn(UUID workspaceId) {
        var propertyId = portfolio.createProperty(workspaceId, "Testowa 1",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        return portfolio.addUnit(propertyId, "M1", new BigDecimal("2500"));
    }
}
