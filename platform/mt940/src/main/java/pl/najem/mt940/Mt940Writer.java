package pl.najem.mt940;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Writes statements as the MT940 text a bank would send. Pure: no state, no clock, no I/O. */
public final class Mt940Writer {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd");

    private Mt940Writer() {
    }

    public static String write(List<Mt940Statement> statements) {
        StringBuilder text = new StringBuilder();
        for (Mt940Statement statement : statements) {
            text.append(write(statement));
        }
        return text.toString();
    }

    public static String write(Mt940Statement statement) {
        LocalDate opening = statement.lines().isEmpty()
            ? LocalDate.EPOCH : statement.lines().getFirst().valueDate();
        LocalDate closing = statement.lines().isEmpty()
            ? opening : statement.lines().getLast().valueDate();

        StringBuilder text = new StringBuilder()
            .append(":20:NAJEM").append(digitsOf(statement.statementNumber())).append('\n')
            .append(":25:").append(statement.account()).append('\n')
            .append(":28C:").append(statement.statementNumber()).append('\n')
            .append(":60F:").append(balance(statement.openingBalance(), opening, statement.currency())).append('\n');

        for (Mt940Line line : statement.lines()) {
            text.append(entry(line)).append('\n').append(information(line)).append('\n');
        }
        return text
            .append(":62F:")
            .append(balance(statement.openingBalance().add(total(statement)), closing, statement.currency()))
            .append('\n')
            .append("-\n")
            .toString();
    }

    private static String entry(Mt940Line line) {
        StringBuilder entry = new StringBuilder(":61:")
            .append(YYMMDD.format(line.valueDate()))
            .append(MMDD.format(line.bookingDate()))
            .append(line.mark())
            .append(amount(line.amount()))
            .append("NTRF")
            .append(line.customerReference());
        if (line.bankReference() != null) {
            entry.append("//").append(line.bankReference());
        }
        return entry.toString();
    }

    private static String information(Mt940Line line) {
        StringBuilder information = new StringBuilder(":86:~20").append(line.remittanceInfo());
        if (line.counterpartyName() != null) {
            information.append("~32").append(line.counterpartyName());
        }
        if (line.counterpartyIban() != null) {
            information.append("~38").append(line.counterpartyIban());
        }
        return information.toString();
    }

    /**
     * Credits add, debits subtract. A statement must agree with its own entries: the closing
     * balance is the opening balance plus this, never a figure stored alongside them.
     */
    private static BigDecimal total(Mt940Statement statement) {
        BigDecimal total = BigDecimal.ZERO;
        for (Mt940Line line : statement.lines()) {
            total = line.mark() == Mt940Mark.C ? total.add(line.amount()) : total.subtract(line.amount());
        }
        return total;
    }

    /** A balance states its direction with a mark, exactly as an entry does — never with a sign. */
    private static String balance(BigDecimal total, LocalDate date, String currency) {
        char mark = total.signum() < 0 ? 'D' : 'C';
        return mark + YYMMDD.format(date) + currency + amount(total.abs());
    }

    private static String amount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString().replace('.', ',');
    }

    /** :20: is a free transaction reference; deriving it from the statement number keeps it stable. */
    private static String digitsOf(String statementNumber) {
        return statementNumber == null ? "1" : statementNumber.replaceAll("\\D", "");
    }
}
