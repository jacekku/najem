package pl.najem.acc.adapter.pm;

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
import pl.najem.acc.application.LedgerService;
import pl.najem.acc.application.WarningService;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Tenancy Accounting ACL carries the contract's split through to the ledger. PM owns the
 * agreement facts; the ACL translates them, and never reconstructs what PM already knows.
 */
@Testcontainers
class TenancyActivatedHandlerTest {

    private static final UUID WS = WorkspaceContext.DEV_WORKSPACE_ID;
    private static final LocalDate START = LocalDate.of(2026, 11, 1);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static TenancyActivatedHandler handler;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        handler = new TenancyActivatedHandler(new LedgerService(store, jdbc, new WarningService(jdbc)));
    }

    @Test
    void aContractualSplitBecomesOneChargeLinePerComponent() {
        var tenancyId = UUID.randomUUID();

        handler.handle(new TenancyActivatedEvent(WS, tenancyId, UUID.randomUUID(), START,
            new BigDecimal("3000"), true, new BigDecimal("2400"), new BigDecimal("300"),
            new BigDecimal("300"), "ZWYKLY", new BigDecimal("4800"), "NAJEM/ACL1/2026"));

        assertThat(componentsOf(tenancyId)).containsExactlyInAnyOrder("rent", "adminFee", "mediaAdvance");
        assertThat(amountOf(tenancyId, "rent")).isEqualByComparingTo("2400");
    }

    @Test
    void anUnsplitContractCollapsesIntoASingleRentLine() {
        var tenancyId = UUID.randomUUID();

        handler.handle(new TenancyActivatedEvent(WS, tenancyId, UUID.randomUUID(), START,
            new BigDecimal("3000"), false, null, null, null,
            "OKAZJONALNY", new BigDecimal("6000"), "NAJEM/ACL2/2026"));

        assertThat(componentsOf(tenancyId)).containsExactly("rent");
        assertThat(amountOf(tenancyId, "rent")).isEqualByComparingTo("3000");
    }

    private static List<String> componentsOf(UUID tenancyId) {
        return jdbc.queryForList("select component from acc_charge where tenancy_id = ?",
            String.class, tenancyId);
    }

    private static BigDecimal amountOf(UUID tenancyId, String component) {
        return jdbc.queryForObject("select amount from acc_charge where tenancy_id = ? and component = ?",
            BigDecimal.class, tenancyId, component);
    }
}
