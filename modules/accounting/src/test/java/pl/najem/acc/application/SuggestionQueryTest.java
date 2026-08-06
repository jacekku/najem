package pl.najem.acc.application;

import pl.najem.acc.adapter.persistence.PostgresAccounting;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a manager is shown before they confirm a match.
 *
 * <p>The ladder's tiers exist so that a certainty and a guess are not treated alike. That only works
 * if the difference reaches the person deciding: a tier without the evidence it was derived from is
 * just a number, and a manager cannot check a number. The read used to return two UUIDs.
 */
@Testcontainers
@Tag("integration")
class SuggestionQueryTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final UUID OTHER_WS = UUID.fromString("00000000-0000-0000-0000-0000000000fa");
    private static final LocalDate DUE = LocalDate.of(2027, 5, 10);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static InvoiceService invoicing;
    static IngestionService laddered;
    static SuggestionQuery suggestions;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        invoicing = PostgresAccounting.invoiceService(store, jdbc, PostgresAccounting.warningService(jdbc));
        laddered = PostgresAccounting.ingestionService((since, iban) -> List.of(), store, jdbc, MatchingPolicy.tiersOn());
        suggestions = new pl.najem.acc.adapter.persistence.PostgresSuggestionQuery(jdbc);
    }

    /** Tier 1: the payer quoted the reference exactly, and both references read the same. */
    @Test
    void anExactMatchCarriesItsTierAndTheEvidenceForIt() {
        var tenancyId = UUID.randomUUID();
        invoicing.postRent(WS, tenancyId, new BigDecimal("2500"), DUE, "NAJEM/Q1/2027");
        laddered.ingest(WS, credit("tx-q1", "2500", "NAJEM/Q1/2027", "Anna Kowalska", "PL99"));

        var row = only("tx-q1");
        assertThat(row.tier()).isEqualTo(1);
        assertThat(row.paidAmount()).isEqualByComparingTo("2500");
        assertThat(row.chargedAmount()).isEqualByComparingTo("2500");
        assertThat(row.outstanding()).isEqualByComparingTo("2500");
        assertThat(row.quotedReference()).isEqualTo("NAJEM/Q1/2027");
        assertThat(row.expectedReference()).isEqualTo("NAJEM/Q1/2027");
        assertThat(row.payerName()).isEqualTo("Anna Kowalska");
        assertThat(row.tenancyId()).isEqualTo(tenancyId);
        assertThat(row.isPartPayment()).isFalse();
    }

    /**
     * Tier 2 matches on the reference appearing somewhere in the title and does not constrain the
     * amount at all. So a suggestion can settle a fraction of the charge, and the manager has to see
     * that <em>before</em> confirming rather than discovering it afterwards.
     */
    @Test
    void aPartPaymentSaysSoBeforeItIsConfirmed() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("3000"), DUE, "NAJEM/Q2/2027");
        laddered.ingest(WS, credit("tx-q2", "500", "przelew najem/q2/2027 czynsz", "Jan Nowak", "PL88"));

        var row = only("tx-q2");
        assertThat(row.tier()).isEqualTo(2);
        assertThat(row.paidAmount()).isEqualByComparingTo("500");
        assertThat(row.outstanding()).isEqualByComparingTo("3000");
        assertThat(row.isPartPayment()).isTrue();
        // The two references differ, and that difference is the judgement being handed over.
        assertThat(row.quotedReference()).isNotEqualTo(row.expectedReference());
    }

    /** Certainties first: a manager working down the list spends attention where it is needed. */
    @Test
    void theMostConfidentSuggestionsComeFirst() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("1000"), DUE, "NAJEM/Q3/2027");
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("1000"), DUE, "NAJEM/Q4/2027");
        laddered.ingest(WS, credit("tx-q4", "1000", "oplata najem/q4/2027", "B", "PL77"));
        laddered.ingest(WS, credit("tx-q3", "1000", "NAJEM/Q3/2027", "A", "PL76"));

        var tiers = suggestions.forWorkspace(WS).stream().map(SuggestionQuery.Row::tier).toList();

        assertThat(tiers).isSorted();
    }

    /** A suggestion belongs to one agency's books. */
    @Test
    void anotherWorkspaceSeesNothing() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("900"), DUE, "NAJEM/Q5/2027");
        laddered.ingest(WS, credit("tx-q5", "900", "NAJEM/Q5/2027", "C", "PL75"));

        assertThat(suggestions.forWorkspace(OTHER_WS)).isEmpty();
    }

    private static SuggestionQuery.Row only(String externalId) {
        UUID paymentId = jdbc.queryForObject(
            "select payment_id from acc_payment where external_id = ?", UUID.class, externalId);
        return suggestions.forWorkspace(WS).stream()
            .filter(row -> row.paymentId().equals(paymentId))
            .findFirst().orElseThrow(() -> new AssertionError("no suggestion for " + externalId));
    }

    private static BankLine credit(String id, String amount, String title, String payer, String iban) {
        return new BankLine(id, new BigDecimal(amount), title, DUE, payer, iban, null, null,
            "CRDT", "PLN");
    }
}
