package pl.najem.mt940;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One statement line: a :61: entry with the :86: information that follows it.
 *
 * <p>{@code counterpartyName} and {@code counterpartyIban} are null when the statement carries an
 * unstructured :86:, which is normal and not an error — many banks send free text.
 */
public record Mt940Line(LocalDate bookingDate, LocalDate valueDate, BigDecimal amount, Mt940Mark mark,
                        String bankReference, String customerReference, String remittanceInfo,
                        String counterpartyName, String counterpartyIban) {

    public Mt940Line {
        if (amount == null || amount.signum() < 0) {
            throw new Mt940FormatException("Amount must be present and non-negative, was: " + amount);
        }
    }
}
