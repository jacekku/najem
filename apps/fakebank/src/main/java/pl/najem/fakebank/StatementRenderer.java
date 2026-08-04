package pl.najem.fakebank;

import org.springframework.stereotype.Component;
import pl.najem.mt940.Mt940Line;
import pl.najem.mt940.Mt940Mark;
import pl.najem.mt940.Mt940Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders seeded transactions as MT940 statements.
 *
 * <p>One statement per currency, currencies in alphabetical order: MT940 states the currency once,
 * on the balance fields, so transactions in two currencies cannot share a statement. The ordering is
 * what keeps the export deterministic.
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
        Map<String, List<Mt940Line>> byCurrency = new TreeMap<>();
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
