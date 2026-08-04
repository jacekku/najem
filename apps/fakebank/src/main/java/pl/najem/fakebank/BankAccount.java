package pl.najem.fakebank;

import java.math.BigDecimal;

/**
 * An account this bank holds, as distinct from an IBAN that merely appears in a transaction.
 *
 * <p>Until now FakeBank had no such thing: an account existed because something had been seeded
 * into it, so it could not be opened empty, could not be named, and could not carry a balance it
 * had before the first transfer. A statement's {@code :60F:} was therefore always zero.
 *
 * @param currency       the account's own currency, and the one its opening balance is stated in.
 *                       A transfer in another currency is still accepted — MT940 renders it as a
 *                       separate statement — but it opens at zero, because this account has no
 *                       opening balance in that currency and inventing one would be a number from
 *                       nowhere.
 * @param openingBalance signed: positive is a credit balance, negative an overdraft. The mark MT940
 *                       uses is derived at write time, so direction is never stored twice.
 */
public record BankAccount(String iban, String holder, String currency, BigDecimal openingBalance) {

    public BankAccount {
        if (iban == null || iban.isBlank()) {
            throw new IllegalArgumentException("An account needs an IBAN");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("An account needs a currency — it is what its balance is stated in");
        }
        iban = iban.replace(" ", "").toUpperCase();
        currency = currency.trim().toUpperCase();
        openingBalance = openingBalance == null ? BigDecimal.ZERO : openingBalance;
    }

    /** The opening balance for a statement in {@code statementCurrency}. Zero unless it is ours. */
    public BigDecimal openingBalanceIn(String statementCurrency) {
        return currency.equals(statementCurrency) ? openingBalance : BigDecimal.ZERO;
    }
}
