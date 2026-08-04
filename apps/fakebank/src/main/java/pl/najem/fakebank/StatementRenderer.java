package pl.najem.fakebank;

import org.springframework.stereotype.Component;
import pl.najem.mt940.Mt940Line;
import pl.najem.mt940.Mt940Mark;
import pl.najem.mt940.Mt940Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Renders seeded transactions as MT940 statements.
 *
 * <p>One statement per currency: MT940 states the currency once, on the balance fields, so
 * transactions in two currencies cannot share a statement.
 *
 * <p><strong>Currencies are ordered by first appearance, not alphabetically</strong>, and that is
 * load-bearing rather than cosmetic. Accounting derives its deduplication key from
 * {@code statement.statementNumber()}, so a statement that changes number re-keys every payment on
 * it — the same transfers ingest a second time as new payments, and per-workspace uniqueness on
 * that key means the duplicates collide with nothing and nobody is told.
 *
 * <p>Alphabetical ordering made that reachable: a EUR line added to an account holding PLN took the
 * number PLN had, silently re-keying every PLN payment already ingested. First appearance cannot do
 * that, because appending only ever adds a number at the end.
 *
 * <p>It is equally deterministic — the same transactions in the same order always produce the same
 * numbering, which is what the export needs. It does <em>not</em> survive a caller that reorders or
 * front-truncates the list (the {@code since} filter can), and the durable fix for that is
 * accounting keying on something the transaction owns rather than on where it landed.
 */
@Component
public class StatementRenderer {

    private static final String NO_CUSTOMER_REFERENCE = "NONREF";

    public List<Mt940Statement> render(String iban, List<BankTransactionDto> transactions) {
        return render(iban, transactions, currency -> java.math.BigDecimal.ZERO);
    }

    /**
     * @param openingBalanceOf the account's opening balance for a given statement currency. A
     *                         statement in a currency the account was not opened in gets zero —
     *                         see {@link BankAccount#openingBalanceIn}.
     */
    public List<Mt940Statement> render(String iban, List<BankTransactionDto> transactions,
                                       java.util.function.Function<String, java.math.BigDecimal> openingBalanceOf) {
        Map<String, List<Mt940Line>> byCurrency = new LinkedHashMap<>();
        for (BankTransactionDto transaction : transactions) {
            byCurrency.computeIfAbsent(currencyOf(transaction), currency -> new ArrayList<>())
                .add(line(transaction));
        }
        List<Mt940Statement> statements = new ArrayList<>(byCurrency.size());
        int number = 1;
        for (Map.Entry<String, List<Mt940Line>> entry : byCurrency.entrySet()) {
            statements.add(new Mt940Statement(iban, number++ + "/1", entry.getKey(),
                openingBalanceOf.apply(entry.getKey()), entry.getValue()));
        }
        return statements;
    }

    private Mt940Line line(BankTransactionDto transaction) {
        return new Mt940Line(
            transaction.bookingDate(),
            transaction.valueDate() == null ? transaction.bookingDate() : transaction.valueDate(),
            transaction.amount(),
            "DBIT".equals(transaction.creditDebitIndicator()) ? Mt940Mark.D : Mt940Mark.C,
            transaction.bankReference(),
            NO_CUSTOMER_REFERENCE,
            transaction.title() == null ? "" : transaction.title(),
            transaction.counterpartyName(),
            transaction.counterpartyIban());
    }

    private static String currencyOf(BankTransactionDto transaction) {
        return transaction.currency() == null ? "PLN" : transaction.currency();
    }
}
