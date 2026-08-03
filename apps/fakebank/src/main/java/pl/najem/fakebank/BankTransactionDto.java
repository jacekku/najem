package pl.najem.fakebank;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One bank statement line as FakeBank serves it.
 *
 * <p>The first four fields are always present — they are the Phase 0 shape that accounting's
 * FakeBankAdapter already consumes. The remaining six are nullable additions requested by
 * najem-accounting for matching ladder tiers 2-4; they are omitted from the JSON when null so the
 * Phase 0 payload is reproduced exactly.
 *
 * <p>Amounts are ALWAYS positive. Direction lives solely in {@code creditDebitIndicator}
 * ({@code CRDT} / {@code DBIT}, ISO 20022) — never duplicated as a sign.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BankTransactionDto(String id,
                                 BigDecimal amount,
                                 String title,
                                 LocalDate bookingDate,
                                 String counterpartyName,
                                 String counterpartyIban,
                                 String bankReference,
                                 LocalDate valueDate,
                                 String creditDebitIndicator,
                                 String currency) {

    /** A line carrying only the Phase 0 fields. */
    public static BankTransactionDto plain(String id, BigDecimal amount, String title, LocalDate bookingDate) {
        return new BankTransactionDto(id, amount, title, bookingDate, null, null, null, null, null, null);
    }
}
