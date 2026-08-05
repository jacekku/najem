package pl.najem.acc.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.TestWorkspace;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whose money is this?
 *
 * <p>The statement port used to fetch from one account named in configuration and hand every line to
 * whichever workspace called. Each workspace then matched those lines against its own charges,
 * correctly and independently — so one transfer could be suggested against a charge in agency A and
 * a charge in agency B, and if both managers accepted it, money that exists once read as paid in two
 * sets of books. No attacker, no missing header, no omitted predicate: every query was scoped and
 * the composition was still wrong.
 *
 * <p>The account is a property of the workspace, and a workspace without one may not ingest at all.
 */
@Testcontainers
@Tag("integration")
class WorkspaceBankAccountTest {

    private static final UUID AGENCY_A = TestWorkspace.ID;
    private static final UUID AGENCY_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String IBAN_A = "PL11111111111111111111111111";
    private static final String IBAN_B = "PL22222222222222222222222222";
    private static final LocalDate BOOKED = LocalDate.of(2027, 3, 5);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;

    /** Remembers which account it was asked for, and answers only for that account. */
    static class AccountAwareBank implements BankStatementPort {

        final List<String> asked = new ArrayList<>();

        @Override
        public List<BankLine> fetchSince(LocalDate since, String iban) {
            asked.add(iban);
            return List.of(new BankLine(iban + "-tx-1", new BigDecimal("2000"),
                "NAJEM/W1/2027", BOOKED));
        }
    }

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
    }

    /**
     * The defect, stated as the behaviour that must not happen: two workspaces fetching must read
     * two different accounts, and neither may see the other's line.
     */
    @Test
    void eachWorkspaceIngestsItsOwnAccountRatherThanTheOneInConfiguration() {
        register(AGENCY_A, IBAN_A);
        register(AGENCY_B, IBAN_B);
        var bank = new AccountAwareBank();
        var ingestion = new IngestionService(bank, store, jdbc);

        ingestion.fetchAndIngest(AGENCY_A);
        ingestion.fetchAndIngest(AGENCY_B);

        assertThat(bank.asked).containsExactly(IBAN_A, IBAN_B);
        assertThat(externalIdsIn(AGENCY_A)).containsExactly(IBAN_A + "-tx-1");
        assertThat(externalIdsIn(AGENCY_B)).containsExactly(IBAN_B + "-tx-1");
    }

    /**
     * Rule 7, in the one place where getting it wrong misattributes money rather than merely leaking
     * a read. A workspace nobody has given an account to must not fall back to anybody else's.
     */
    @Test
    void aWorkspaceWithNoRegisteredAccountRefusesToIngestAnything() {
        var unconfigured = UUID.randomUUID();
        var bank = new AccountAwareBank();
        var ingestion = new IngestionService(bank, store, jdbc);

        assertThatThrownBy(() -> ingestion.fetchAndIngest(unconfigured))
            .isInstanceOf(NoBankAccountRegisteredException.class)
            .hasMessageContaining(unconfigured.toString());
        assertThat(bank.asked).isEmpty();
        assertThat(externalIdsIn(unconfigured)).isEmpty();
    }

    /**
     * Registration must not reopen by the back door what configuration used to do by the front one:
     * two workspaces naming one account both ingest every line in it.
     */
    @Test
    void oneAccountCannotBeRegisteredToTwoWorkspaces() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        String shared = "PL33333333333333333333333333";
        register(first, shared);

        assertThatThrownBy(() -> register(second, shared))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    private static void register(UUID workspaceId, String iban) {
        jdbc.update("""
            insert into acc_workspace_account(workspace_id, iban, registered_on) values (?,?,?)
            """, workspaceId, iban, BOOKED);
    }

    private static List<String> externalIdsIn(UUID workspaceId) {
        return jdbc.queryForList(
            "select external_id from acc_payment where workspace_id = ? order by external_id",
            String.class, workspaceId);
    }
}
