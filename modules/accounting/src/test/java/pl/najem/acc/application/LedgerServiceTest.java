package pl.najem.acc.application;

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
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.domain.ChargePosted;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LedgerServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static LedgerService service;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        service = new LedgerService(store, jdbc);
    }

    @Test
    void postsRentChargeWithProjectionAndAwaitingStatus() {
        var tenancyId = UUID.randomUUID();

        var chargeId = service.postRentCharge(tenancyId, new BigDecimal("2500"),
            LocalDate.of(2026, 9, 1), "NAJEM/M1/2026");

        assertThat(store.load(tenancyId).events())
            .containsExactly(new ChargePosted(chargeId, tenancyId, "rent",
                new BigDecimal("2500"), LocalDate.of(2026, 9, 1)));
        assertThat(jdbc.queryForObject(
            "select allocated from acc_charge where charge_id = ?", Boolean.class, chargeId)).isFalse();
        assertThat(jdbc.queryForObject(
            "select status from acc_tenancy_status where tenancy_id = ?", String.class, tenancyId))
            .isEqualTo("awaiting");
    }
}
