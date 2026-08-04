package pl.najem.mt940;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mt940ReaderTest {

    private static final String ON_TIME = """
        :20:NAJEM1
        :25:PL61109010140000071219812874
        :28C:1/1
        :60F:C260901PLN0,00
        :61:2609010901C2500,00NTRFNONREF//BNP00123456
        :86:~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026~38PL00000000000000000123456789
        :62F:C260901PLN2500,00
        -
        """;

    @Test
    void readsTheStatementHeader() {
        Mt940Statement statement = Mt940Reader.read(ON_TIME).getFirst();

        assertThat(statement.account()).isEqualTo("PL61109010140000071219812874");
        assertThat(statement.statementNumber()).isEqualTo("1/1");
        assertThat(statement.currency()).isEqualTo("PLN");
    }

    @Test
    void readsACreditLineWithoutEverSigningTheAmount() {
        Mt940Line line = Mt940Reader.read(ON_TIME).getFirst().lines().getFirst();

        assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(line.bookingDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(line.amount()).isEqualByComparingTo("2500.00");
        assertThat(line.mark()).isEqualTo(Mt940Mark.C);
        assertThat(line.bankReference()).isEqualTo("BNP00123456");
        assertThat(line.customerReference()).isEqualTo("NONREF");
    }

    @Test
    void readsAStructured86IntoRemittanceAndCounterparty() {
        Mt940Line line = Mt940Reader.read(ON_TIME).getFirst().lines().getFirst();

        assertThat(line.remittanceInfo()).isEqualTo("NAJEM/M1/2026");
        assertThat(line.counterpartyName()).isEqualTo("NAJEMCA NAJEM-M1-2026");
        assertThat(line.counterpartyIban()).isEqualTo("PL00000000000000000123456789");
    }

    @Test
    void readsADebitAsAPositiveAmountWithADebitMark() {
        String text = ON_TIME.replace("C2500,00NTRFNONREF", "D287,43NTRFNONREF");

        Mt940Line line = Mt940Reader.read(text).getFirst().lines().getFirst();

        assertThat(line.amount()).isEqualByComparingTo("287.43");
        assertThat(line.mark()).isEqualTo(Mt940Mark.D);
    }

    @Test
    void treatsAReversalMarkAsItsUnderlyingDirection() {
        String text = ON_TIME.replace("C2500,00NTRF", "RC2500,00NTRF");

        assertThat(Mt940Reader.read(text).getFirst().lines().getFirst().mark()).isEqualTo(Mt940Mark.C);
    }

    @Test
    void keepsUnstructured86AsRemittanceAndLeavesCounterpartyUnknown() {
        String text = ON_TIME.replace(
            ":86:~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026~38PL00000000000000000123456789",
            ":86:PRZELEW NAJEM M1 2026");

        Mt940Line line = Mt940Reader.read(text).getFirst().lines().getFirst();

        assertThat(line.remittanceInfo()).isEqualTo("PRZELEW NAJEM M1 2026");
        assertThat(line.counterpartyName()).isNull();
        assertThat(line.counterpartyIban()).isNull();
    }

    @Test
    void joinsRemittanceSubfieldsInOrder() {
        String text = ON_TIME.replace("~20NAJEM/M1/2026~32", "~20NAJEM~21/M1/2026~32");

        assertThat(Mt940Reader.read(text).getFirst().lines().getFirst().remittanceInfo())
            .isEqualTo("NAJEM/M1/2026");
    }

    @Test
    void readsALineWhoseInformationFieldIsAbsent() {
        String text = ON_TIME.replace(
            ":86:~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026~38PL00000000000000000123456789\n", "");

        Mt940Line line = Mt940Reader.read(text).getFirst().lines().getFirst();

        assertThat(line.remittanceInfo()).isEmpty();
        assertThat(line.counterpartyName()).isNull();
    }

    @Test
    void infersTheBookingYearBackwardsWhenAStatementStraddlesNewYear() {
        String text = ON_TIME
            .replace(":61:2609010901C", ":61:2601021230C")
            .replace(":60F:C260901PLN", ":60F:C260102PLN")
            .replace(":62F:C260901PLN", ":62F:C260102PLN");

        Mt940Line line = Mt940Reader.read(text).getFirst().lines().getFirst();

        assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(line.bookingDate()).isEqualTo(LocalDate.of(2025, 12, 30));
    }

    @Test
    void readsSeveralStatementsFromOneFile() {
        List<Mt940Statement> statements = Mt940Reader.read(ON_TIME + ON_TIME.replace(":28C:1/1", ":28C:2/1"));

        assertThat(statements).hasSize(2);
        assertThat(statements.get(1).statementNumber()).isEqualTo("2/1");
    }

    @Test
    void rejectsAnAmountItCannotRead() {
        String text = ON_TIME.replace("C2500,00NTRF", "Ctwo-thousandNTRF");

        assertThatThrownBy(() -> Mt940Reader.read(text))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining("two-thousand");
    }

    @Test
    void rejectsAStatementWithNoAccount() {
        String text = ON_TIME.replace(":25:PL61109010140000071219812874\n", "");

        assertThatThrownBy(() -> Mt940Reader.read(text))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining(":25:");
    }
}
