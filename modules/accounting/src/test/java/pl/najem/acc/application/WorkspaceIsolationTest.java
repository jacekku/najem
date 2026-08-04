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
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workspace is a hard tenancy boundary: two agencies may legitimately use the same payment
 * reference, and money from one must never be matched against the other's charges.
 */
@Testcontainers
class WorkspaceIsolationTest {

    private static final UUID AGENCY_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID AGENCY_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
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
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        ledger = new LedgerService(store, jdbc, new WarningService(jdbc));
        ingestion = new IngestionService(since -> List.of(), store, jdbc);
        reconciliation = new ReconciliationService(store, jdbc);
    }

    @Test
    void paymentDoesNotMatchAnIdenticalChargeInAnotherWorkspace() {
        ledger.postRentCharge(AGENCY_A, UUID.randomUUID(), new BigDecimal("2500"),
            LocalDate.of(2026, 9, 10), "NAJEM/SHARED/2026");

        ingestion.ingest(AGENCY_B, new BankLine("tx-cross", new BigDecimal("2500"),
            "NAJEM/SHARED/2026", LocalDate.of(2026, 9, 3)));

        assertThat(jdbc.queryForObject(
            "select status from acc_payment where external_id = 'tx-cross'", String.class))
            .isEqualTo("unmatched");
    }

    @Test
    void theSameExternalIdMayArriveInTwoWorkspaces() {
        var line = new BankLine("tx-same-id", new BigDecimal("100"), "NO/MATCH", LocalDate.of(2026, 9, 3));

        ingestion.ingest(AGENCY_A, line);
        ingestion.ingest(AGENCY_B, line);

        assertThat(jdbc.queryForObject(
            "select count(*) from acc_payment where external_id = 'tx-same-id'", Integer.class))
            .isEqualTo(2);
    }

    @Test
    void confirmingFromTheWrongWorkspaceIsRefused() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(AGENCY_A, tenancyId, new BigDecimal("3000"),
            LocalDate.of(2026, 9, 10), "NAJEM/WRONG-WS/2026");
        ingestion.ingest(AGENCY_A, new BankLine("tx-ws", new BigDecimal("3000"),
            "NAJEM/WRONG-WS/2026", LocalDate.of(2026, 9, 3)));
        UUID paymentId = jdbc.queryForObject(
            "select payment_id from acc_payment where external_id = 'tx-ws'", UUID.class);

        assertThat(jdbc.queryForObject(
            "select count(*) from acc_payment where payment_id = ? and workspace_id = ?",
            Integer.class, paymentId, AGENCY_B)).isZero();

        reconciliation.confirm(AGENCY_B, paymentId);

        assertThat(jdbc.queryForObject(
            "select status from acc_payment where payment_id = ?", String.class, paymentId))
            .isEqualTo("suggested");
    }

    @Test
    void boardOfOneWorkspaceNeverShowsAnother() {
        var tenancyA = UUID.randomUUID();
        var tenancyB = UUID.randomUUID();
        ledger.postRentCharge(AGENCY_A, tenancyA, new BigDecimal("1000"),
            LocalDate.of(2026, 9, 10), "NAJEM/BOARD-A/2026");
        ledger.postRentCharge(AGENCY_B, tenancyB, new BigDecimal("1000"),
            LocalDate.of(2026, 9, 10), "NAJEM/BOARD-B/2026");

        var tenanciesOfA = jdbc.queryForList(
            "select tenancy_id from acc_tenancy_status where workspace_id = ?", UUID.class, AGENCY_A);

        assertThat(tenanciesOfA).contains(tenancyA).doesNotContain(tenancyB);
    }
}
