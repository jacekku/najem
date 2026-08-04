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

    /** No page may offer a way to change what the bank holds; seeding stays on the API. */
    @Test
    void theScreensAreReadOnly() throws Exception {
        seed("ui-9", "2500.00", "NAJEM", "CRDT");

        String html = mvc.perform(get("/accounts/{iban}", iban))
            .andReturn().getResponse().getContentAsString();

        assertThat(html)
            .as("a form on this page could put the demo into a state the API did not")
            .doesNotContain("<form");
    }
}
