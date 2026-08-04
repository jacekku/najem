package pl.najem.mt940;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mt940LineTest {

    @Test
    void rejectsANegativeAmountBecauseDirectionLivesInTheMark() {
        assertThatThrownBy(() -> new Mt940Line(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), new BigDecimal("-1.00"),
                Mt940Mark.C, "BNP1", "NONREF", "x", null, null))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining("-1.00");
    }

    @Test
    void keepsTheMarkAsTheOnlyStatementOfDirection() {
        Mt940Line debit = new Mt940Line(
            LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4), new BigDecimal("287.43"),
            Mt940Mark.D, "BNP2", "NONREF", "OPLATA ZA MEDIA", "PGNIG", "PL10");

        assertThat(debit.amount()).isEqualByComparingTo("287.43");
        assertThat(debit.mark()).isEqualTo(Mt940Mark.D);
    }
}
