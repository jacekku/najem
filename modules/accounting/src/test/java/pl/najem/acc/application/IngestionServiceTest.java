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
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class IngestionServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static LedgerService ledger;
    static IngestionService ingestion;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        ledger = new LedgerService(store, jdbc);
        ingestion = new IngestionService(since -> List.of(), store, jdbc);
    }

    @Test
    void exactReferenceAndAmountMatchProducesSuggestion() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(WorkspaceContext.DEV_WORKSPACE_ID, tenancyId, new BigDecimal("2500"), LocalDate.of(2026, 9, 10), "NAJEM/T1/2026");

        ingestion.ingest(WorkspaceContext.DEV_WORKSPACE_ID, new BankLine("tx-m1", new BigDecimal("2500"), "NAJEM/T1/2026",
            LocalDate.of(2026, 9, 3)));

        assertThat(jdbc.queryForObject(
            "select status from acc_payment where external_id = 'tx-m1'", String.class))
            .isEqualTo("suggested");
        assertThat(jdbc.queryForObject("select count(*) from acc_suggestion", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameExternalIdIsIngestedOnce() {
        var line = new BankLine("tx-dup", new BigDecimal("100"), "NO/MATCH", LocalDate.of(2026, 9, 3));

        ingestion.ingest(WorkspaceContext.DEV_WORKSPACE_ID, line);
        ingestion.ingest(WorkspaceContext.DEV_WORKSPACE_ID, line);

        assertThat(jdbc.queryForObject(
            "select count(*) from acc_payment where external_id = 'tx-dup'", Integer.class)).isEqualTo(1);
    }

    @Test
    void nonMatchingLineStaysUnmatched() {
        ingestion.ingest(WorkspaceContext.DEV_WORKSPACE_ID, new BankLine("tx-um", new BigDecimal("999"), "GIBBERISH",
            LocalDate.of(2026, 9, 3)));

        assertThat(jdbc.queryForObject(
            "select status from acc_payment where external_id = 'tx-um'", String.class))
            .isEqualTo("unmatched");
    }
}
