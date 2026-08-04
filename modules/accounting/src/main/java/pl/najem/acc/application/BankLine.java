package pl.najem.acc.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One statement line as it reaches the ledger, whatever produced it — a bank API poll or an
 * uploaded MT940 file. Both paths converge here.
 *
 * <p><strong>{@code amount} is always positive and direction lives solely in
 * {@code creditDebitIndicator}</strong> ({@code CRDT} / {@code DBIT}, ISO 20022). Nothing in
 * accounting may read the sign of the amount to decide direction: every source we consume states
 * amounts positively, so a signum test reads an outgoing debit as an incoming payment.
 *
 * <p>{@code counterpartyName}, {@code counterpartyIban}, {@code bankReference} and
 * {@code valueDate} are nullable — a free-text MT940 {@code :86:} carries no counterparty at all,
 * and that is an ordinary bank sending less rather than a malformed line.
 *
 * <p>Direction and currency are normalised here rather than at each reader, so a source that omits
 * them is read as the incoming złoty payment the four-field shape has always meant.
 */
public record BankLine(String externalId,
                       BigDecimal amount,
                       String title,
                       LocalDate bookingDate,
                       String counterpartyName,
                       String counterpartyIban,
                       String bankReference,
                       LocalDate valueDate,
                       String creditDebitIndicator,
                       String currency) {

    public static final String CREDIT = "CRDT";
    public static final String DEBIT = "DBIT";
    public static final String ZLOTY = "PLN";

    public BankLine {
        creditDebitIndicator = creditDebitIndicator == null ? CREDIT : creditDebitIndicator;
        currency = currency == null ? ZLOTY : currency;
    }

    /**
     * The four-field shape this record began as: an incoming złoty payment carrying nothing the
     * bank chose not to tell us. Kept as a constructor rather than a factory so the widening is
     * purely additive — every existing caller, in this module and in reporting's tripwire tests,
     * compiles unchanged.
     */
    public BankLine(String externalId, BigDecimal amount, String title, LocalDate bookingDate) {
        this(externalId, amount, title, bookingDate, null, null, null, null, CREDIT, ZLOTY);
    }

    /** Money coming in. Only an incoming line can settle a tenant's charge. */
    public boolean isCredit() {
        return CREDIT.equals(creditDebitIndicator);
    }

    /** The ledger holds złoty; a line in any other currency is a fact for a human, not a match. */
    public boolean isZloty() {
        return ZLOTY.equals(currency);
    }
}
