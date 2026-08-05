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
import pl.najem.acc.adapter.persistence.PostgresAccounting;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.MatchTier;
import pl.najem.acc.domain.WarningKind;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The matching ladder: exact reference and amount, then reference alone, then the account the payer
 * paid from last time, then a human. Every rung produces a suggestion the manager confirms — the
 * ladder never allocates money by itself, and the rungs below the first are OFF until switched on.
 */
@Testcontainers
class MatchingLadderTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final UUID OTHER_WS = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final LocalDate DUE = LocalDate.of(2027, 5, 10);
    /** Each test uses its own account: tier 3 learns, so a shared one would leak between them. */
    private static final String ANNAS_ACCOUNT = "PL27114020040000300201355387";
    private static final String UNKNOWN_ACCOUNT = "PL33124000010000400000000001";
    private static final String OWN_ACCOUNT = "PL33124000010000400000000002";

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static InvoiceService invoicing;
    static IngestionService laddered;
    static IngestionService tierOneOnly;
    static ReconciliationService reconciliation;
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
        invoicing = PostgresAccounting.invoiceService(store, jdbc, warnings);
        reconciliation = PostgresAccounting.reconciliationService(store, jdbc);
        laddered = new IngestionService((since, iban) -> List.of(), store, jdbc, MatchingPolicy.tiersOn());
        tierOneOnly = new IngestionService((since, iban) -> List.of(), store, jdbc, MatchingPolicy.tierOneOnly());
    }

    /**
     * The default. A payer who mangles the reference is a tier-2 case, so with the ladder off there
     * is nothing to suggest and the line waits for a human — today's behaviour, unchanged.
     */
    @Test
    void withTheLadderOffAMangledReferenceFindsNothing() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("2000"), DUE, "NAJEM/ML1/2027");

        tierOneOnly.ingest(WS, credit("tx-ml1", "2000", "najem ml1 2027", null));

        assertThat(statusOf("tx-ml1")).isEqualTo("unmatched");
        assertThat(tierOf("tx-ml1")).isNull();
    }

    /**
     * Tier 2, first shape: the amount is right and the reference is recognisable once separators and
     * case are discarded — which is how a careless payer types it into their banking app.
     */
    @Test
    void tierTwoRecognisesAReferenceTypedCarelessly() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("2100"), DUE, "NAJEM/ML2/2027");

        laddered.ingest(WS, credit("tx-ml2", "2100", "najem ml2 2027", null));

        assertThat(statusOf("tx-ml2")).isEqualTo("suggested");
        assertThat(tierOf("tx-ml2")).isEqualTo(MatchTier.REFERENCE.number());
    }

    /**
     * Tier 2, second shape: the reference is exact but the amount is not. A part payment is still
     * that tenant's money — refusing to suggest it would send an obvious match to the manual queue.
     */
    @Test
    void tierTwoMatchesOnReferenceWhenTheAmountFallsShort() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("2200"), DUE, "NAJEM/ML3/2027");

        laddered.ingest(WS, credit("tx-ml3", "1320.00", "NAJEM/ML3/2027", null));

        assertThat(statusOf("tx-ml3")).isEqualTo("suggested");
        assertThat(tierOf("tx-ml3")).isEqualTo(MatchTier.REFERENCE.number());
    }

    /** An exact match is a better answer than a fuzzy one, so tier 1 must be tried first. */
    @Test
    void tierOneWinsWhenBothCouldApply() {
        var exact = invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("2300"), DUE,
            "NAJEM/ML4/2027");
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("9999"), DUE.minusDays(30),
            "NAJEM/ML4/2027");

        laddered.ingest(WS, credit("tx-ml4", "2300", "NAJEM/ML4/2027", null));

        assertThat(tierOf("tx-ml4")).isEqualTo(MatchTier.EXACT.number());
        assertThat(suggestedChargeOf("tx-ml4")).isEqualTo(exact);
    }

    /**
     * A short reference is a substring of a longer one, so a payer naming September also, literally,
     * names January. The most specific claim wins — otherwise a full month's rent gets suggested
     * against a small old charge, and the tier badge reads as "slightly less certain" rather than
     * "possibly the wrong charge entirely". Found by najem-integrations reviewing tiers 2-4.
     */
    @Test
    void tierTwoPrefersTheLongestReferenceThePayerNamed() {
        var tenancyId = UUID.randomUUID();
        invoicing.postRent(WS, tenancyId, new BigDecimal("500"), DUE, "NAJEM/MS1");
        var named = invoicing.postRent(WS, tenancyId, new BigDecimal("2500"), DUE.plusMonths(8),
            "NAJEM/MS1/2027/09");

        laddered.ingest(WS, credit("tx-ms1", "2500", "przelew najem ms1 2027 09", null));

        assertThat(suggestedChargeOf("tx-ms1")).isEqualTo(named);
    }

    /** Tier 4: an unrecognised payer with no reference is the manual queue, not a guess. */
    @Test
    void anUnknownPayerWithoutAReferenceReachesTheManualQueue() {
        invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("2400"), DUE, "NAJEM/ML5/2027");

        laddered.ingest(WS, credit("tx-ml5", "2400", "", UNKNOWN_ACCOUNT));

        assertThat(statusOf("tx-ml5")).isEqualTo("unmatched");
        assertThat(tierOf("tx-ml5")).isNull();
    }

    /**
     * Tier 3: confirming a match teaches the ledger which account that tenancy pays from — including
     * when a third party pays on the tenant's behalf. The next reference-less transfer from the same
     * account is recognised.
     */
    @Test
    void confirmingAMatchTeachesTheLedgerThePayersAccount() {
        var tenancyId = UUID.randomUUID();
        invoicing.postRent(WS, tenancyId, new BigDecimal("2500"), DUE, "NAJEM/ML6/2027");
        laddered.ingest(WS, credit("tx-ml6", "2500", "NAJEM/ML6/2027", ANNAS_ACCOUNT));
        reconciliation.confirm(WS, paymentOf("tx-ml6"));

        var next = invoicing.postRent(WS, tenancyId, new BigDecimal("2500"), DUE.plusMonths(1),
            "NAJEM/ML6B/2027");
        laddered.ingest(WS, credit("tx-ml6b", "2500", "", ANNAS_ACCOUNT));

        assertThat(statusOf("tx-ml6b")).isEqualTo("suggested");
        assertThat(tierOf("tx-ml6b")).isEqualTo(MatchTier.REMEMBERED_PAYER.number());
        assertThat(suggestedChargeOf("tx-ml6b")).isEqualTo(next);
    }

    /** A remembered payer is one agency's knowledge. It must not resolve in another's books. */
    @Test
    void aRememberedPayerDoesNotCrossTheWorkspaceBoundary() {
        var tenancyId = UUID.randomUUID();
        var payerAccount = "PL10105000997603123456789123";
        invoicing.postRent(WS, tenancyId, new BigDecimal("2600"), DUE, "NAJEM/ML7/2027");
        laddered.ingest(WS, credit("tx-ml7", "2600", "NAJEM/ML7/2027", payerAccount));
        reconciliation.confirm(WS, paymentOf("tx-ml7"));

        invoicing.postRent(OTHER_WS, UUID.randomUUID(), new BigDecimal("2600"), DUE, "OBCE/ML7/2027");
        laddered.ingest(OTHER_WS, credit("tx-ml7-other", "2600", "", payerAccount));

        assertThat(statusOf("tx-ml7-other")).isEqualTo("unmatched");
    }

    /**
     * A free-text MT940 {@code :86:} carries no counterparty. Tier 3 has nothing to look up and must
     * say so rather than matching every other line that also arrived without one.
     */
    @Test
    void tierThreeDoesNotFireOnAMissingCounterparty() {
        var tenancyId = UUID.randomUUID();
        invoicing.postRent(WS, tenancyId, new BigDecimal("2700"), DUE, "NAJEM/ML8/2027");
        laddered.ingest(WS, credit("tx-ml8", "2700", "NAJEM/ML8/2027", "PL99999999999999999999999999"));
        reconciliation.confirm(WS, paymentOf("tx-ml8"));

        invoicing.postRent(WS, tenancyId, new BigDecimal("2700"), DUE.plusMonths(1), "NAJEM/ML8B/2027");
        laddered.ingest(WS, credit("tx-ml8b", "2700", "", null));

        assertThat(statusOf("tx-ml8b")).isEqualTo("unmatched");
    }

    /**
     * A parent guaranteeing two children's flats pays for both from one account — the ordinary case,
     * not the exotic one. Both associations are remembered: forgetting the first would make tier 3
     * confidently suggest the second flat's charge for the first flat's payment.
     */
    @Test
    void anAccountMayPayForMoreThanOneTenancy() {
        var flatA = UUID.randomUUID();
        var flatB = UUID.randomUUID();
        var guarantor = "PL83101010230000261395100000";
        confirmedPaymentFrom(guarantor, flatA, "2800", "NAJEM/ML9A/2027", "tx-ml9a");
        confirmedPaymentFrom(guarantor, flatB, "2900", "NAJEM/ML9B/2027", "tx-ml9b");

        assertThat(jdbc.queryForList("""
            select tenancy_id from acc_payer_account where workspace_id = ? and counterparty_iban = ?
            """, UUID.class, WS, guarantor)).containsExactlyInAnyOrder(flatA, flatB);
    }

    /**
     * With two tenancies behind one account there is no honest answer from the account alone, so
     * tier 3 declines and the line goes to a human. Guessing would be wrong half the time and would
     * look like the ledger's own opinion.
     */
    @Test
    void tierThreeDeclinesToGuessBetweenTwoTenancies() {
        var flatA = UUID.randomUUID();
        var flatB = UUID.randomUUID();
        var guarantor = "PL83101010230000261395100001";
        confirmedPaymentFrom(guarantor, flatA, "3100", "NAJEM/MG1A/2027", "tx-mg1a");
        confirmedPaymentFrom(guarantor, flatB, "3200", "NAJEM/MG1B/2027", "tx-mg1b");
        invoicing.postRent(WS, flatA, new BigDecimal("3100"), DUE.plusMonths(1), "NAJEM/MG1C/2027");

        laddered.ingest(WS, credit("tx-mg1c", "3100", "", guarantor));

        assertThat(statusOf("tx-mg1c")).isEqualTo("unmatched");
    }

    /**
     * The manager is told once, when the account stops being able to identify a tenancy on its own —
     * not every month. That is the moment tier 3 goes quiet for it, and the moment worth knowing.
     */
    @Test
    void anAccountBecomingAmbiguousWarnsOnceRatherThanEveryMonth() {
        var flatA = UUID.randomUUID();
        var flatB = UUID.randomUUID();
        var guarantor = "PL83101010230000261395100002";
        confirmedPaymentFrom(guarantor, flatA, "3300", "NAJEM/MG2A/2027", "tx-mg2a");
        confirmedPaymentFrom(guarantor, flatB, "3400", "NAJEM/MG2B/2027", "tx-mg2b");
        confirmedPaymentFrom(guarantor, flatB, "3400", "NAJEM/MG2C/2027", "tx-mg2c");

        var raised = warnings.unseen(WS).stream()
            .filter(w -> w.kind() == WarningKind.PAYER_ACCOUNT_AMBIGUOUS)
            .filter(w -> w.detail().contains(guarantor))
            .toList();
        assertThat(raised).singleElement()
            .satisfies(w -> assertThat(w.detail()).contains(flatA.toString()).contains(flatB.toString()));
    }

    /** A first association is how tier 3 learns anything. It is not news. */
    @Test
    void learningAnAccountForTheFirstTimeWarnsAboutNothing() {
        var tenancyId = UUID.randomUUID();
        var account = "PL83101010230000261395100003";
        confirmedPaymentFrom(account, tenancyId, "3500", "NAJEM/MG3/2027", "tx-mg3");

        assertThat(warnings.unseen(WS)).noneSatisfy(w ->
            assertThat(w.detail()).contains(account));
    }

    private static void confirmedPaymentFrom(String payerIban, UUID tenancyId, String amount,
                                             String reference, String externalId) {
        invoicing.postRent(WS, tenancyId, new BigDecimal(amount), DUE, reference);
        laddered.ingest(WS, credit(externalId, amount, reference, payerIban));
        reconciliation.confirm(WS, paymentOf(externalId));
    }

    /** Automation is built and off: the ladder suggests, and only a manager moves money. */
    @Test
    void aSuggestionIsNeverAllocatedByTheLadderItself() {
        var chargeId = invoicing.postRent(WS, UUID.randomUUID(), new BigDecimal("3000"), DUE,
            "NAJEM/ML10/2027");

        laddered.ingest(WS, credit("tx-ml10", "3000", "NAJEM/ML10/2027", OWN_ACCOUNT));

        assertThat(statusOf("tx-ml10")).isEqualTo("suggested");
        assertThat(jdbc.queryForObject("select allocated from acc_charge where charge_id = ?",
            Boolean.class, chargeId)).isFalse();
        assertThat(MatchingPolicy.tiersOn().autoConfirm()).isFalse();
    }

    private static BankLine credit(String externalId, String amount, String title, String payerIban) {
        return new BankLine(externalId, new BigDecimal(amount), title, DUE,
            payerIban == null ? null : "PLATNIK", payerIban, null, DUE, "CRDT", "PLN");
    }

    private static String statusOf(String externalId) {
        return jdbc.queryForObject("select status from acc_payment where external_id = ?",
            String.class, externalId);
    }

    private static Integer tierOf(String externalId) {
        var tiers = jdbc.queryForList("""
            select s.tier from acc_suggestion s join acc_payment p using (payment_id)
            where p.external_id = ?
            """, Integer.class, externalId);
        return tiers.isEmpty() ? null : tiers.getFirst();
    }

    private static UUID suggestedChargeOf(String externalId) {
        return jdbc.queryForObject("""
            select s.charge_id from acc_suggestion s join acc_payment p using (payment_id)
            where p.external_id = ?
            """, UUID.class, externalId);
    }

    private static UUID paymentOf(String externalId) {
        return jdbc.queryForObject("select payment_id from acc_payment where external_id = ?",
            UUID.class, externalId);
    }
}
