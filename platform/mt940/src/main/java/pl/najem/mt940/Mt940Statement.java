package pl.najem.mt940;

import java.math.BigDecimal;
import java.util.List;

/**
 * One :20:…:62F: statement block.
 *
 * <p>A statement carries exactly one currency — MT940 states it on the balance fields, not per
 * line — so transactions in two currencies are two statements, never one.
 *
 * @param openingBalance the {@code :60F:} figure, signed. Positive is a credit balance, negative a
 *                       debit one; the writer converts that to the mark MT940 actually uses, since
 *                       the format states direction with a mark and never with a sign. The closing
 *                       balance is not carried because it is not independent — it is this figure
 *                       plus the statement's own entries, and storing it would allow a statement
 *                       that disagrees with itself.
 */
public record Mt940Statement(String account, String statementNumber, String currency,
                             BigDecimal openingBalance, List<Mt940Line> lines) {

    public Mt940Statement {
        lines = List.copyOf(lines);
        openingBalance = openingBalance == null ? BigDecimal.ZERO : openingBalance;
    }

    /**
     * A statement that opens at zero — the shape this record had before opening balances existed.
     * Kept so the widening is purely additive and every existing caller compiles unchanged.
     */
    public Mt940Statement(String account, String statementNumber, String currency, List<Mt940Line> lines) {
        this(account, statementNumber, currency, BigDecimal.ZERO, lines);
    }
}
