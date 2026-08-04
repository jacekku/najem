package pl.najem.fakebank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import pl.najem.mt940.Mt940Line;
import pl.najem.mt940.Mt940Mark;
import pl.najem.mt940.Mt940Reader;
import pl.najem.mt940.Mt940Statement;
import pl.najem.mt940.Mt940Writer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every scenario survives the trip out through MT940 and back.
 *
 * <p>External ids are not compared: an id is a property of FakeBank's transport, not of the
 * transaction, and a real bank has never heard of ours.
 */
class StatementRoundTripTest {

    private static final String IBAN = "PL61109010140000071219812874";
    private static final String REFERENCE = "NAJEM/M1/2026";
    private static final BigDecimal AMOUNT = new BigDecimal("2500.00");
    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 1);

    private final ScenarioCatalog catalog = new ScenarioCatalog();
    private final StatementRenderer renderer = new StatementRenderer();

    static Set<String> scenarios() {
        return new ScenarioCatalog().names();
    }

    private List<Mt940Line> exportAndReread(List<BankTransactionDto> seeded) {
        String text = Mt940Writer.write(renderer.render(IBAN, seeded));
        List<Mt940Line> lines = new ArrayList<>();
        for (Mt940Statement statement : Mt940Reader.read(text)) {
            lines.addAll(statement.lines());
        }
        return lines;
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void everyScenarioSurvivesTheRoundTrip(String scenario) {
        List<BankTransactionDto> seeded =
            catalog.generate(new ScenarioRequest(scenario, IBAN, REFERENCE, AMOUNT, ANCHOR));

        List<Mt940Line> reread = exportAndReread(seeded);

        assertThat(reread).hasSameSizeAs(seeded);
        for (int i = 0; i < seeded.size(); i++) {
            BankTransactionDto expected = seeded.get(i);
            Mt940Line actual = reread.get(i);
            assertThat(actual.amount()).as("amount of %s line %d", scenario, i)
                .isEqualByComparingTo(expected.amount());
            assertThat(actual.bookingDate()).as("booking date of %s line %d", scenario, i)
                .isEqualTo(expected.bookingDate());
            assertThat(actual.valueDate()).as("value date of %s line %d", scenario, i)
                .isEqualTo(expected.valueDate());
            assertThat(actual.remittanceInfo()).as("title of %s line %d", scenario, i)
                .isEqualTo(expected.title());
            assertThat(actual.counterpartyName()).as("counterparty of %s line %d", scenario, i)
                .isEqualTo(expected.counterpartyName());
            assertThat(actual.counterpartyIban()).as("counterparty IBAN of %s line %d", scenario, i)
                .isEqualTo(expected.counterpartyIban());
            assertThat(actual.bankReference()).as("bank reference of %s line %d", scenario, i)
                .isEqualTo(expected.bankReference());
            assertThat(actual.mark().name()).as("direction of %s line %d", scenario, i)
                .isEqualTo("DBIT".equals(expected.creditDebitIndicator()) ? "D" : "C");
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void noScenarioEverProducesASignedAmount(String scenario) {
        List<Mt940Line> reread = exportAndReread(
            catalog.generate(new ScenarioRequest(scenario, IBAN, REFERENCE, AMOUNT, ANCHOR)));

        assertThat(reread).allSatisfy(line -> assertThat(line.amount()).isPositive());
    }

    @Test
    void theOutgoingDebitStaysADebit() {
        List<Mt940Line> reread = exportAndReread(
            catalog.generate(new ScenarioRequest("outgoing-debit", IBAN, REFERENCE, AMOUNT, ANCHOR)));

        assertThat(reread).singleElement()
            .satisfies(line -> assertThat(line.mark()).isEqualTo(Mt940Mark.D));
    }

    @Test
    void exportingTwiceProducesByteIdenticalText() {
        List<BankTransactionDto> seeded =
            catalog.generate(new ScenarioRequest("partial-then-topup", IBAN, REFERENCE, AMOUNT, ANCHOR));

        assertThat(Mt940Writer.write(renderer.render(IBAN, seeded)))
            .isEqualTo(Mt940Writer.write(renderer.render(IBAN, seeded)));
    }
}
