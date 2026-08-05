package pl.najem.acc.application;

import pl.najem.acc.adapter.persistence.PostgresAccounting;
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
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bank line is more than four fields, and the extra ones decide correctness rather than
 * decoration: direction says whether money came in at all, currency says whether it is money this
 * ledger can hold, and the counterparty is the only thing tiers 3-4 can match on when the tenant
 * types no reference.
 *
 * <p>Direction is read from {@code creditDebitIndicator} and never from the sign of the amount —
 * FakeBank's amounts are always positive by contract, so a signum test would read every outgoing
 * debit as an incoming payment.
 */
@Testcontainers
class BankLineDetailTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate BOOKED = LocalDate.of(2027, 3, 10);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static InvoiceService invoicing;
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
        invoicing = PostgresAccounting.invoiceService(store, jdbc, new WarningService(jdbc));
        ingestion = new IngestionService((since, iban) -> java.util.List.of(), store, jdbc);
    }

    @Test
    void aWidenedLineKeepsEveryFieldTheLadderWillNeed() {
        ingestion.ingest(WS, new BankLine("tx-bd1", new BigDecimal("2000"), "NAJEM/BD1/2027", BOOKED,
            "ANNA KOWALSKA", "PL61109010140000071219812874", "BNP00012345",
            LocalDate.of(2027, 3, 11), "CRDT", "PLN"));

        var row = jdbc.queryForMap(
            "select counterparty_name, counterparty_iban, bank_reference, value_date, direction, currency"
                + " from acc_payment where external_id = 'tx-bd1'");
        assertThat(row.get("counterparty_name")).isEqualTo("ANNA KOWALSKA");
        assertThat(row.get("counterparty_iban")).isEqualTo("PL61109010140000071219812874");
        assertThat(row.get("bank_reference")).isEqualTo("BNP00012345");
        assertThat(((java.sql.Date) row.get("value_date")).toLocalDate())
            .isEqualTo(LocalDate.of(2027, 3, 11));
        assertThat(row.get("direction")).isEqualTo("CRDT");
        assertThat(row.get("currency")).isEqualTo("PLN");
    }

    /**
     * An outgoing debit is a real bank fact and is recorded — but it is money leaving, so it can
     * never settle a tenant's charge, however exactly its reference and amount line up.
     */
    @Test
    void anOutgoingDebitIsRecordedAndNeverSuggested() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("287.43"), BOOKED, "NAJEM/BD2/2027");

        ingestion.ingest(WS, new BankLine("tx-bd2", new BigDecimal("287.43"), "NAJEM/BD2/2027", BOOKED,
            "PGNIG OBROT DETALICZNY", "PL00000000000000000000000001", "BNP00099999",
            BOOKED, "DBIT", "PLN"));

        assertThat(jdbc.queryForObject("select status from acc_payment where external_id = 'tx-bd2'",
            String.class)).isEqualTo("unmatched");
        assertThat(jdbc.queryForObject(
            "select count(*) from acc_suggestion s join acc_payment p using (payment_id)"
                + " where p.external_id = 'tx-bd2'", Integer.class)).isZero();
    }

    /** The ledger holds złoty. A euro line is a fact to be looked at by a human, not a match. */
    @Test
    void aForeignCurrencyLineIsNeverSuggestedAgainstAZlotyCharge() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("3000"), BOOKED, "NAJEM/BD3/2027");

        ingestion.ingest(WS, new BankLine("tx-bd3", new BigDecimal("3000"), "NAJEM/BD3/2027", BOOKED,
            "NAJEMCA BD3", "PL00000000000000000000000002", "BNP00088888", BOOKED, "CRDT", "EUR"));

        assertThat(jdbc.queryForObject("select status from acc_payment where external_id = 'tx-bd3'",
            String.class)).isEqualTo("unmatched");
    }

    /**
     * A free-text {@code :86:} on an MT940 statement yields no counterparty at all. That is an
     * ordinary bank sending less, not a malformed line — ingestion must take it.
     */
    @Test
    void aLineWithoutACounterpartyIngestsRatherThanFailing() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("1800"), BOOKED, "NAJEM/BD4/2027");

        ingestion.ingest(WS, new BankLine("tx-bd4", new BigDecimal("1800"), "NAJEM/BD4/2027", BOOKED,
            null, null, null, null, "CRDT", "PLN"));

        assertThat(jdbc.queryForObject("select status from acc_payment where external_id = 'tx-bd4'",
            String.class)).isEqualTo("suggested");
        assertThat(jdbc.queryForObject(
            "select counterparty_iban from acc_payment where external_id = 'tx-bd4'", String.class))
            .isNull();
    }

    /**
     * The four-field shape predates the widening and still arrives from anything that has not been
     * updated. A line that states no direction is an incoming payment — which is what those four
     * fields have always meant — rather than a line of unknown direction that quietly stops matching.
     */
    @Test
    void theNarrowShapeStillReadsAsAnIncomingZlotyPayment() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("1900"), BOOKED, "NAJEM/BD5/2027");

        ingestion.ingest(WS, new BankLine("tx-bd5", new BigDecimal("1900"), "NAJEM/BD5/2027", BOOKED));

        assertThat(jdbc.queryForObject("select status from acc_payment where external_id = 'tx-bd5'",
            String.class)).isEqualTo("suggested");
        var row = jdbc.queryForMap("select direction, currency from acc_payment where external_id = 'tx-bd5'");
        assertThat(row.get("direction")).isEqualTo("CRDT");
        assertThat(row.get("currency")).isEqualTo("PLN");
    }
}
