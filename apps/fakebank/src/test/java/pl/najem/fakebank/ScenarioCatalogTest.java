package pl.najem.fakebank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioCatalogTest {

    private static final String IBAN = "PL61109010140000071219812874";
    private static final String REFERENCE = "NAJEM/M1/2026";
    private static final BigDecimal AMOUNT = new BigDecimal("2500.00");
    private static final LocalDate DUE = LocalDate.of(2026, 9, 1);

    private final ScenarioCatalog catalog = new ScenarioCatalog();

    private List<BankTransactionDto> generate(String name) {
        return catalog.generate(new ScenarioRequest(name, IBAN, REFERENCE, AMOUNT, DUE));
    }

    @Test
    void onTimePaysExactlyOnTheDueDate() {
        List<BankTransactionDto> lines = generate("on-time");

        assertThat(lines).hasSize(1);
        BankTransactionDto line = lines.getFirst();
        assertThat(line.amount()).isEqualByComparingTo("2500.00");
        assertThat(line.title()).isEqualTo(REFERENCE);
        assertThat(line.bookingDate()).isEqualTo(DUE);
        assertThat(line.valueDate()).isEqualTo(DUE);
        assertThat(line.creditDebitIndicator()).isEqualTo("CRDT");
        assertThat(line.currency()).isEqualTo("PLN");
        assertThat(line.counterpartyIban()).startsWith("PL").hasSize(28);
        assertThat(line.bankReference()).startsWith("BNP").isNotEqualTo(REFERENCE);
    }

    @Test
    void lateBooksAfterTheDueDateAndSplitsValueDate() {
        List<BankTransactionDto> lines = generate("late");

        assertThat(lines).hasSize(1);
        BankTransactionDto line = lines.getFirst();
        assertThat(line.bookingDate()).isEqualTo(DUE.plusDays(6));
        assertThat(line.valueDate()).isEqualTo(DUE.plusDays(8));
        assertThat(line.amount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void partialPaysSixtyPercent() {
        List<BankTransactionDto> lines = generate("partial");

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().amount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void partialThenTopupSettlesInTwoTransfersSummingToTheCharge() {
        List<BankTransactionDto> lines = generate("partial-then-topup");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).amount()).isEqualByComparingTo("1500.00");
        assertThat(lines.get(0).bookingDate()).isEqualTo(DUE);
        assertThat(lines.get(1).amount()).isEqualByComparingTo("1000.00");
        assertThat(lines.get(1).bookingDate()).isEqualTo(DUE.plusDays(4));
        assertThat(lines.get(0).amount().add(lines.get(1).amount())).isEqualByComparingTo(AMOUNT);
    }

    @Test
    void overpayPaysTwentyPercentTooMuch() {
        assertThat(generate("overpay").getFirst().amount()).isEqualByComparingTo("3000.00");
    }

    @Test
    void externalIdsAreDeterministicAndUniqueWithinAScenario() {
        List<BankTransactionDto> first = generate("partial-then-topup");
        List<BankTransactionDto> second = generate("partial-then-topup");

        assertThat(first.stream().map(BankTransactionDto::id))
            .containsExactly("partial-then-topup/NAJEM-M1-2026/0", "partial-then-topup/NAJEM-M1-2026/1");
        assertThat(second.stream().map(BankTransactionDto::id))
            .containsExactlyElementsOf(first.stream().map(BankTransactionDto::id).toList());
    }

    @Test
    void theSameTenantKeepsTheSameAccountAcrossScenarios() {
        assertThat(generate("on-time").getFirst().counterpartyIban())
            .isEqualTo(generate("late").getFirst().counterpartyIban());
    }

    @Test
    void unknownScenarioIsRejected() {
        assertThatThrownBy(() -> generate("no-such-scenario"))
            .isInstanceOf(UnknownScenarioException.class)
            .hasMessageContaining("no-such-scenario");
    }

    @Test
    void wrongReferenceKeepsTheAmountButManglesTheTitle() {
        BankTransactionDto line = generate("wrong-reference").getFirst();

        assertThat(line.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(line.title()).isEqualTo("najem m1 2026").isNotEqualTo(REFERENCE);
    }

    @Test
    void noReferenceLeavesTheTitleEmpty() {
        BankTransactionDto line = generate("no-reference").getFirst();

        assertThat(line.title()).isEmpty();
        assertThat(line.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(line.counterpartyIban()).isNotBlank();
    }

    @Test
    void duplicateSeedsTheSamePaymentTwiceUnderDistinctBankIdentifiers() {
        List<BankTransactionDto> lines = generate("duplicate");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).amount()).isEqualByComparingTo(lines.get(1).amount());
        assertThat(lines.get(0).title()).isEqualTo(lines.get(1).title());
        assertThat(lines.get(0).bookingDate()).isEqualTo(lines.get(1).bookingDate());
        assertThat(lines.get(0).id()).isNotEqualTo(lines.get(1).id());
        assertThat(lines.get(0).bankReference()).isNotEqualTo(lines.get(1).bankReference());
    }

    @Test
    void reversalCreditsThenTakesTheMoneyBack() {
        List<BankTransactionDto> lines = generate("reversal");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).creditDebitIndicator()).isEqualTo("CRDT");
        assertThat(lines.get(1).creditDebitIndicator()).isEqualTo("DBIT");
        assertThat(lines.get(1).amount())
            .as("the debit is positive; direction lives in the indicator alone")
            .isEqualByComparingTo(lines.get(0).amount());
        assertThat(lines.get(1).bookingDate()).isEqualTo(DUE.plusDays(3));
        assertThat(lines.get(1).title()).isEqualTo("ZWROT " + REFERENCE);
    }

    @Test
    void lumpSumCoversTwoReferencesInOneTransfer() {
        BankTransactionDto line = generate("lump-sum").getFirst();

        assertThat(line.amount()).isEqualByComparingTo("5000.00");
        assertThat(line.title()).isEqualTo(REFERENCE + " " + REFERENCE + "/2");
    }

    @Test
    void thirdPartyPayerUsesADifferentAccountAndNamesADifferentPerson() {
        BankTransactionDto line = generate("third-party-payer").getFirst();

        assertThat(line.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(line.title()).isEmpty();
        assertThat(line.counterpartyName()).isEqualTo("ANNA KOWALSKA");
        assertThat(line.counterpartyIban())
            .as("the payer is not the tenant, so the account must differ")
            .isNotEqualTo(generate("on-time").getFirst().counterpartyIban());
    }

    @Test
    void thirdPartyPayerKeepsTheSameAccountAcrossMonths() {
        LocalDate nextMonth = DUE.plusMonths(1);

        String september = generate("third-party-payer").getFirst().counterpartyIban();
        String october = catalog.generate(
                new ScenarioRequest("third-party-payer", IBAN, REFERENCE, AMOUNT, nextMonth))
            .getFirst().counterpartyIban();

        assertThat(october).isEqualTo(september);
    }

    @Test
    void outgoingDebitIsAUtilityPaymentThatIsNotRent() {
        BankTransactionDto line = generate("outgoing-debit").getFirst();

        assertThat(line.creditDebitIndicator()).isEqualTo("DBIT");
        assertThat(line.amount()).isEqualByComparingTo("287.43");
        assertThat(line.title()).isEqualTo("OPLATA ZA MEDIA");
        assertThat(line.counterpartyName()).isEqualTo("PGNIG OBROT DETALICZNY");
        assertThat(line.bookingDate()).isEqualTo(DUE.plusDays(1));
    }

    @Test
    void foreignCurrencyCreditsInEuro() {
        BankTransactionDto line = generate("foreign-currency").getFirst();

        assertThat(line.currency()).isEqualTo("EUR");
        assertThat(line.creditDebitIndicator()).isEqualTo("CRDT");
        assertThat(line.title()).isEqualTo(REFERENCE);
    }

    @Test
    void everyAdvertisedScenarioGenerates() {
        assertThat(catalog.names()).hasSize(13);
        assertThat(catalog.names()).allSatisfy(name -> assertThat(generate(name)).isNotEmpty());
    }

    @Test
    void everyLineCarriesAPositiveAmount() {
        assertThat(catalog.names()).allSatisfy(name ->
            assertThat(generate(name)).allSatisfy(line ->
                assertThat(line.amount()).isPositive()));
    }
}
