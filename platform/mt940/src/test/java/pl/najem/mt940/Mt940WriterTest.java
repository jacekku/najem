package pl.najem.mt940;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Mt940WriterTest {

    private static Mt940Line credit(String amount, String remittance) {
        return new Mt940Line(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), new BigDecimal(amount),
            Mt940Mark.C, "BNP00123456", "NONREF", remittance, "NAJEMCA NAJEM-M1-2026", "PL99");
    }

    @Test
    void writesTheTagsABankWouldSend() {
        String text = Mt940Writer.write(
            new Mt940Statement("PL61", "1/1", "PLN", List.of(credit("2500.00", "NAJEM/M1/2026"))));

        assertThat(text.lines()).contains(
            ":25:PL61",
            ":28C:1/1",
            ":60F:C260901PLN0,00",
            ":61:2609010901C2500,00NTRFNONREF//BNP00123456",
            ":86:~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026~38PL99",
            ":62F:C260901PLN2500,00",
            "-");
    }

    @Test
    void subtractsDebitsFromTheClosingBalance() {
        Mt940Line debit = new Mt940Line(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 2),
            new BigDecimal("287.43"), Mt940Mark.D, "BNP2", "NONREF", "OPLATA ZA MEDIA", "PGNIG", "PL10");

        String text = Mt940Writer.write(
            new Mt940Statement("PL61", "1/1", "PLN", List.of(credit("2500.00", "x"), debit)));

        assertThat(text.lines()).contains(":62F:C260902PLN2212,57");
    }

    @Test
    void writesANegativeClosingBalanceAsADebitMarkNotAMinusSign() {
        Mt940Line debit = new Mt940Line(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 2),
            new BigDecimal("100.00"), Mt940Mark.D, "BNP2", "NONREF", "x", null, null);

        String text = Mt940Writer.write(new Mt940Statement("PL61", "1/1", "PLN", List.of(debit)));

        assertThat(text.lines()).contains(":62F:D260902PLN100,00");
    }

    @Test
    void omitsCounterpartySubfieldsWhenTheyAreUnknown() {
        Mt940Line anonymous = new Mt940Line(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1),
            new BigDecimal("10.00"), Mt940Mark.C, "BNP3", "NONREF", "ANON", null, null);

        String text = Mt940Writer.write(new Mt940Statement("PL61", "1/1", "PLN", List.of(anonymous)));

        assertThat(text.lines()).contains(":86:~20ANON");
    }

    @Test
    void roundTripsEveryFieldThroughTheReader() {
        Mt940Statement original = new Mt940Statement("PL61", "1/1", "PLN",
            List.of(credit("2500.00", "NAJEM/M1/2026")));

        Mt940Statement reread = Mt940Reader.read(Mt940Writer.write(original)).getFirst();

        assertThat(reread).isEqualTo(original);
    }

    @Test
    void roundTripsSeveralStatementsInOneFile() {
        Mt940Statement pln = new Mt940Statement("PL61", "1/1", "PLN", List.of(credit("2500.00", "A")));
        Mt940Statement eur = new Mt940Statement("PL61", "2/1", "EUR", List.of(credit("600.00", "B")));

        List<Mt940Statement> reread = Mt940Reader.read(Mt940Writer.write(List.of(pln, eur)));

        assertThat(reread).containsExactly(pln, eur);
    }
}
