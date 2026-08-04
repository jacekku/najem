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
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.WarningKind;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A warning nobody can see is not a warning. Compliance flags raised while charging are persisted
 * in the same transaction as the charge that raised them — queryable, workspace-scoped and
 * restart-proof, rather than a log line that scrolls away.
 */
@Testcontainers
class WarningPersistenceTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final UUID OTHER_WS = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final LocalDate DUE = LocalDate.of(2027, 1, 10);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static LedgerService ledger;
    static WarningService warnings;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        warnings = new WarningService(jdbc);
        ledger = new LedgerService(store, jdbc, warnings);
    }

    @Test
    void chargingWithoutAContractualSplitRaisesAVisibleWarning() {
        var tenancyId = UUID.randomUUID();

        ledger.postMonthlyCharges(WS, tenancyId, MonthlyBreakdown.unsplit(new BigDecimal("3000")),
            DUE, "NAJEM/W1/2027");

        var raised = warnings.unseen(WS).stream()
            .filter(w -> w.tenancyId().equals(tenancyId)).toList();
        assertThat(raised).singleElement().satisfies(w -> {
            assertThat(w.kind()).isEqualTo(WarningKind.COLLAPSE_RULE);
            assertThat(w.detail()).contains("ryczałt");
        });
    }

    @Test
    void aBreakdownMismatchRaisesAWarningCarryingBothFigures() {
        var tenancyId = UUID.randomUUID();

        ledger.postMonthlyCharges(WS, tenancyId,
            MonthlyBreakdown.split(new BigDecimal("3000"), new BigDecimal("2400"),
                new BigDecimal("300"), new BigDecimal("200")),
            DUE, "NAJEM/W2/2027");

        assertThat(warnings.unseen(WS)).filteredOn(w -> w.tenancyId().equals(tenancyId))
            .singleElement().satisfies(w -> {
                assertThat(w.kind()).isEqualTo(WarningKind.BREAKDOWN_MISMATCH);
                assertThat(w.detail()).contains("2900").contains("3000");
            });
    }

    @Test
    void aCleanContractualSplitRaisesNothing() {
        var tenancyId = UUID.randomUUID();

        ledger.postMonthlyCharges(WS, tenancyId,
            MonthlyBreakdown.split(new BigDecimal("3000"), new BigDecimal("2400"),
                new BigDecimal("300"), new BigDecimal("300")),
            DUE, "NAJEM/W3/2027");

        assertThat(warnings.unseen(WS)).noneSatisfy(w -> assertThat(w.tenancyId()).isEqualTo(tenancyId));
    }

    @Test
    void warningsBelongToTheirWorkspace() {
        var tenancyId = UUID.randomUUID();
        ledger.postMonthlyCharges(WS, tenancyId, MonthlyBreakdown.unsplit(new BigDecimal("1500")),
            DUE, "NAJEM/W4/2027");

        assertThat(warnings.unseen(OTHER_WS))
            .noneSatisfy(w -> assertThat(w.tenancyId()).isEqualTo(tenancyId));
    }

    @Test
    void anAcknowledgedWarningLeavesTheUnseenQueue() {
        var tenancyId = UUID.randomUUID();
        ledger.postMonthlyCharges(WS, tenancyId, MonthlyBreakdown.unsplit(new BigDecimal("1600")),
            DUE, "NAJEM/W5/2027");
        var warningId = warnings.unseen(WS).stream()
            .filter(w -> w.tenancyId().equals(tenancyId)).findFirst().orElseThrow().warningId();

        warnings.markSeen(WS, warningId);

        assertThat(warnings.unseen(WS)).noneSatisfy(w -> assertThat(w.warningId()).isEqualTo(warningId));
    }

    /** Acknowledging is a workspace-scoped act: another agency's warning is not yours to silence. */
    @Test
    void anotherWorkspaceCannotAcknowledgeYourWarning() {
        var tenancyId = UUID.randomUUID();
        ledger.postMonthlyCharges(WS, tenancyId, MonthlyBreakdown.unsplit(new BigDecimal("1700")),
            DUE, "NAJEM/W6/2027");
        var warningId = warnings.unseen(WS).stream()
            .filter(w -> w.tenancyId().equals(tenancyId)).findFirst().orElseThrow().warningId();

        warnings.markSeen(OTHER_WS, warningId);

        assertThat(warnings.unseen(WS)).anySatisfy(w -> assertThat(w.warningId()).isEqualTo(warningId));
    }
}
