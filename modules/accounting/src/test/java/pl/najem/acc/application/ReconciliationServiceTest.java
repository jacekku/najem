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
import pl.najem.acc.WorkspaceContext;
import pl.najem.acc.domain.PaymentAllocated;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ReconciliationServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static LedgerService ledger;
    static IngestionService ingestion;
    static ReconciliationService reconciliation;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        ledger = new LedgerService(store, jdbc);
        ingestion = new IngestionService(since -> List.of(), store, jdbc);
        reconciliation = new ReconciliationService(store, jdbc);
    }

    @Test
    void confirmingSuggestionAllocatesAndTurnsBoardGreen() {
        var tenancyId = UUID.randomUUID();
        var chargeId = ledger.postRentCharge(WorkspaceContext.DEV_WORKSPACE_ID, tenancyId, new BigDecimal("2500"),
            LocalDate.of(2026, 9, 10), "NAJEM/T9/2026");
        ingestion.ingest(WorkspaceContext.DEV_WORKSPACE_ID, new BankLine("tx-c1", new BigDecimal("2500"), "NAJEM/T9/2026",
            LocalDate.of(2026, 9, 3)));
        UUID paymentId = jdbc.queryForObject(
            "select payment_id from acc_payment where external_id = 'tx-c1'", UUID.class);

        reconciliation.confirm(WorkspaceContext.DEV_WORKSPACE_ID, paymentId);

        assertThat(store.load(paymentId).events())
            .anySatisfy(e -> assertThat(e).isInstanceOf(PaymentAllocated.class));
        assertThat(jdbc.queryForObject(
            "select allocated from acc_charge where charge_id = ?", Boolean.class, chargeId)).isTrue();
        assertThat(jdbc.queryForObject(
            "select status from acc_tenancy_status where tenancy_id = ?", String.class, tenancyId))
            .isEqualTo("green");
    }
}
