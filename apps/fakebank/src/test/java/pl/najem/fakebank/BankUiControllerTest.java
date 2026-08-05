package pl.najem.fakebank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The screens render, and they render the same data the API serves.
 *
 * <p>A real context rather than a unit test of the controller: the failure worth catching here is a
 * template that does not resolve or an expression that does not evaluate, and neither of those is
 * visible when the model is inspected directly. Thymeleaf failures are runtime failures.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BankUiControllerTest {

    /**
     * A distinct account per test method.
     *
     * <p>{@link TransactionStore} is a singleton and nothing clears it, so tests sharing an IBAN
     * would see each other's lines and the suite would depend on execution order. Deriving the
     * account from the test name makes the isolation structural rather than a convention someone
     * has to keep.
     */
    private String iban;

    private static final String COUNTERPARTY = "PL27114020040000300201355387";

    @BeforeEach
    void ownAccount(TestInfo test) {
        iban = "PLUI%026d".formatted(Math.abs((long) test.getDisplayName().hashCode()));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TransactionStore store;

    private void seed(String id, String amount, String title, String mark) {
        store.add(iban, new BankTransactionDto(id, new BigDecimal(amount), title,
            LocalDate.of(2026, 9, 10), "ANNA KOWALSKA", COUNTERPARTY, "BNP" + id,
            LocalDate.of(2026, 9, 10), mark, "PLN"));
    }

    @Test
    void theAccountsPageListsEverySeededAccount() throws Exception {
        seed("ui-1", "2500.00", "NAJEM/M1/2026", "CRDT");
        store.add(iban + "B", BankTransactionDto.plain("ui-2", new BigDecimal("1200.00"),
            "NAJEM/M2/2026", LocalDate.of(2026, 9, 10)));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(iban)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(iban + "B")));
    }

    /**
     * An empty bank is a state, not a fault. The page must say so in words rather than render an
     * empty table, which reads as something having gone wrong.
     */
    @Test
    void anAccountWithNoTransactionsSaysSoRatherThanShowingAnEmptyTable() throws Exception {
        mvc.perform(get("/accounts/{iban}", "PL00000000000000000000000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("nie ma operacji")));
    }

    @Test
    void theAccountPageShowsTheLineWithItsCounterpartyAndReference() throws Exception {
        seed("ui-3", "2500.00", "NAJEM/M3/2026", "CRDT");

        String html = mvc.perform(get("/accounts/{iban}", iban))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html)
            .contains("NAJEM/M3/2026")
            .contains("ANNA KOWALSKA")
            .contains("BNPui-3")
            .contains("2 500,00");
    }

    /**
     * MT940 amounts are always positive and direction lives in the mark. A debit must therefore be
     * distinguishable on screen, or the page shows a payment out as if it were money in.
     */
    @Test
    void aDebitIsShownAsNegativeAndACreditAsPositive() throws Exception {
        // Grouping is a non-breaking space and the decimal separator a comma, stated explicitly in
        // the template rather than taken from the server's locale -- a Polish page whose numbers
        // depend on the host's default renders 2,500.00 on one machine and 2 500,00 on another.
        seed("ui-4", "300.00", "OPLATA", "DBIT");
        seed("ui-5", "2500.00", "NAJEM", "CRDT");

        String html = mvc.perform(get("/accounts/{iban}", iban))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("−300,00").contains("+2 500,00");
    }

    /**
     * The panel serves the export itself, not a re-rendering of it. If these could differ, looking
     * at the screen would tell you nothing about what accounting receives — which is the only
     * reason this page is worth having.
     */
    @Test
    void theRawPanelServesExactlyWhatTheExportEndpointServes() throws Exception {
        seed("ui-6", "2500.00", "NAJEM/M6/2026", "CRDT");

        String fromUi = mvc.perform(get("/accounts/{iban}/mt940", iban))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String fromApi = mvc.perform(get("/api/accounts/{iban}/statement.mt940", iban))
            .andReturn().getResponse().getContentAsString();

        assertThat(fromUi).isEqualTo(fromApi).contains(":25:" + iban);
    }

    /**
     * Two currencies cannot share an MT940 statement — the currency is stated once, on the balance
     * fields. The page must therefore show two statements, because that is what the file contains.
     */
    @Test
    void transactionsInTwoCurrenciesRenderAsTwoStatements() throws Exception {
        seed("ui-7", "2500.00", "NAJEM", "CRDT");
        store.add(iban, new BankTransactionDto("ui-8", new BigDecimal("600.00"), "RENT",
            LocalDate.of(2026, 9, 11), null, null, "BNPui-8",
            LocalDate.of(2026, 9, 11), "CRDT", "EUR"));

        String html = mvc.perform(get("/accounts/{iban}", iban))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("PLN").contains("EUR")
            .containsPattern("(?s)Wyciąg.*1/1.*Wyciąg.*2/1");
    }

    /**
     * A transfer carrying no payment reference is the case the reconciliation demo turns on — no
     * rule can match it, so a person must. An empty cell reads as a rendering fault; the absence
     * has to be legible as an absence.
     */
    @Test
    void aTransferWithNoReferenceSaysSoRatherThanRenderingBlank() throws Exception {
        store.add(iban, new BankTransactionDto("ui-10", new BigDecimal("4100.00"), null,
            LocalDate.of(2026, 9, 10), "NAJEMCA BEZ TYTULU", COUNTERPARTY, "BNPui-10",
            LocalDate.of(2026, 9, 10), "CRDT", "PLN"));

        String html = mvc.perform(get("/accounts/{iban}", iban))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("bez tytułu");
    }

    /**
     * The screens can now change what the bank holds — deliberately, so a person can build up state
     * by hand during a demonstration. What replaces the old "no forms at all" rule is that a GET
     * never writes: every mutation is POST-redirect-GET, so a refresh re-runs the read.
     */
    @Test
    void bookingRedirectsSoARefreshCannotBookItTwice() throws Exception {
        mvc.perform(post("/accounts/{iban}/transactions", iban)
                .param("amount", "2500.00")
                .param("creditDebitIndicator", "CRDT")
                .param("title", "NAJEM/RE/2026")
                .param("bookingDate", "2026-09-10"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/accounts/" + iban));

        assertThat(store.find(iban, null)).hasSize(1);

        // The redirect target, replayed as a browser refresh would replay it.
        mvc.perform(get("/accounts/{iban}", iban)).andExpect(status().isOk());
        mvc.perform(get("/accounts/{iban}", iban)).andExpect(status().isOk());

        assertThat(store.find(iban, null))
            .as("a GET must never book anything, however many times it is replayed")
            .hasSize(1);
    }

    /** Amounts are positive in MT940 and direction is the indicator. A signed amount is refused. */
    @Test
    void aNegativeAmountIsRefusedRatherThanBookedAsADebit() throws Exception {
        mvc.perform(post("/accounts/{iban}/transactions", iban)
                .param("amount", "-300.00")
                .param("creditDebitIndicator", "CRDT")
                .param("bookingDate", "2026-09-10"))
            .andExpect(status().isOk());

        assertThat(store.find(iban, null)).isEmpty();
    }

    @Test
    void anOpenedAccountAppearsWithItsHolderAndOpeningBalance() throws Exception {
        mvc.perform(post("/accounts")
                .param("iban", iban)
                .param("holder", "Nieruchomości Śródmieście")
                .param("currency", "PLN")
                .param("openingBalance", "1500.00"))
            .andExpect(status().is3xxRedirection());

        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(iban).contains("Nieruchomości Śródmieście").contains("1 500,00");
    }

    /**
     * Re-opening would silently replace an opening balance, and a balance that changes without
     * anything being booked is the one thing a bank must not do.
     */
    @Test
    void openingTheSameAccountTwiceIsRefused() throws Exception {
        mvc.perform(post("/accounts").param("iban", iban).param("currency", "PLN"))
            .andExpect(status().is3xxRedirection());

        String html = mvc.perform(post("/accounts").param("iban", iban).param("currency", "EUR"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("already exists");
    }

    /** The opening balance must reach the file, not just the screen. */
    @Test
    void theOpeningBalanceIsWrittenIntoTheStatement() throws Exception {
        mvc.perform(post("/accounts").param("iban", iban).param("currency", "PLN")
            .param("openingBalance", "1500.00"));
        mvc.perform(post("/accounts/{iban}/transactions", iban)
            .param("amount", "2500.00").param("creditDebitIndicator", "CRDT")
            .param("bookingDate", "2026-09-10"));

        String file = mvc.perform(get("/accounts/{iban}/mt940", iban))
            .andReturn().getResponse().getContentAsString();

        assertThat(file)
            .as("opening balance on :60F:, and :62F: is opening plus the entries")
            .contains(":60F:C260910PLN1500,00")
            .contains(":62F:C260910PLN4000,00");
    }

    /**
     * An account opened in one currency has no opening balance in another, and inventing one would
     * put a number from nowhere into a bank file.
     */
    @Test
    void aStatementInAnotherCurrencyOpensAtZero() throws Exception {
        mvc.perform(post("/accounts").param("iban", iban).param("currency", "PLN")
            .param("openingBalance", "1500.00"));
        mvc.perform(post("/accounts/{iban}/transactions", iban)
            .param("amount", "600.00").param("creditDebitIndicator", "CRDT")
            .param("bookingDate", "2026-09-11").param("currency", "EUR"));

        String file = mvc.perform(get("/accounts/{iban}/mt940", iban))
            .andReturn().getResponse().getContentAsString();

        assertThat(file).contains(":60F:C260911EUR0,00");
    }

    /** An account nobody opened still works, exactly as every account did before the registry. */
    @Test
    void anAccountThatWasOnlySeededStillRendersAndOpensAtZero() throws Exception {
        seed("ui-11", "2500.00", "NAJEM/SEEDED/2026", "CRDT");

        String html = mvc.perform(get("/accounts/{iban}", iban))
            .andReturn().getResponse().getContentAsString();
        String file = mvc.perform(get("/accounts/{iban}/mt940", iban))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Rachunek nieotwarty");
        assertThat(file).contains(":60F:C260910PLN0,00");
    }

    /**
     * Two bookings must never share an id. Accounting deduplicates on it over the JSON port, so a
     * collision means the second transfer is taken for an already-ingested duplicate and dropped —
     * a real payment vanishing with no error anywhere.
     */
    @Test
    void everyBookingGetsItsOwnId() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/accounts/{iban}/transactions", iban)
                .param("amount", "100.00").param("creditDebitIndicator", "CRDT")
                .param("bookingDate", "2026-09-10"));
        }

        assertThat(store.find(iban, null)).extracting(BankTransactionDto::id)
            .doesNotHaveDuplicates().hasSize(5);
    }

    /**
     * Two processes must not mint the same id. FakeBank's store is wiped by a restart and
     * accounting's payments are not, so a repeated id means the next real transfer is taken for an
     * already-ingested duplicate and dropped.
     *
     * <p>Asserted on the run token rather than by restarting a JVM: two controllers stand in for two
     * processes, which is the part of a restart that matters here.
     */
    @Test
    void twoProcessesDoNotMintTheSameId() {
        var one = new BankUiController(store, new StatementRenderer(), new AccountRegistry());
        var two = new BankUiController(store, new StatementRenderer(), new AccountRegistry());

        assertThat(idsFrom(one)).doesNotContainAnyElementsOf(idsFrom(two));
    }

    private static java.util.List<String> idsFrom(BankUiController controller) {
        var flash = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        var ids = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            controller.book("PLRUN", new BigDecimal("100.00"), "CRDT", null,
                LocalDate.of(2026, 9, 10), null, null, null, null, flash);
            ids.add(String.valueOf(flash.getFlashAttributes().get("booked")));
        }
        return ids;
    }
}
