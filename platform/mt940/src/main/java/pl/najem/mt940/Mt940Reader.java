package pl.najem.mt940;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads MT940 text into statements. Pure: no state, no clock, no I/O. */
public final class Mt940Reader {

    /** :61:  value date, optional booking MMDD, mark, optional funds code, amount, type, refs. */
    private static final Pattern ENTRY = Pattern.compile(
        "^(\\d{6})(\\d{4})?(RC|RD|C|D)([A-Z])?([\\d,]+)([A-Z][A-Z0-9]{3})(.*)$");

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    /** :86: subfield: a tilde, two digits, then everything up to the next tilde. */
    private static final Pattern SUBFIELD = Pattern.compile("~(\\d{2})([^~]*)");

    private Mt940Reader() {
    }

    public static List<Mt940Statement> read(String text) {
        List<Mt940Statement> statements = new ArrayList<>();
        List<Mt940Tags.Field> fields = Mt940Tags.split(text);

        String account = null;
        String number = null;
        String currency = null;
        List<Mt940Line> lines = new ArrayList<>();
        Mt940Line pending = null;

        for (Mt940Tags.Field field : fields) {
            switch (field.tag()) {
                case "20" -> {
                    if (account != null || pending != null || !lines.isEmpty()) {
                        pending = flush(statements, account, number, currency, lines, pending);
                        account = null;
                        number = null;
                        currency = null;
                        lines = new ArrayList<>();
                    }
                }
                case "25" -> account = field.value();
                case "28C", "28" -> number = field.value();
                case "60F", "60M" -> currency = balanceCurrency(field.value());
                case "61" -> {
                    if (pending != null) {
                        lines.add(pending);
                    }
                    pending = entry(field.value());
                }
                case "86" -> {
                    if (pending != null) {
                        lines.add(information(pending, field.value()));
                        pending = null;
                    }
                }
                default -> {
                    // :62F:, :64:, :65:, :13D: and friends carry nothing this library exposes.
                }
            }
        }
        flush(statements, account, number, currency, lines, pending);
        return statements;
    }

    private static Mt940Line flush(List<Mt940Statement> statements, String account, String number,
                                   String currency, List<Mt940Line> lines, Mt940Line pending) {
        if (pending != null) {
            lines.add(pending);
        }
        if (account == null) {
            throw new Mt940FormatException("Statement has no account; :25: is required");
        }
        statements.add(new Mt940Statement(account, number, currency, lines));
        return null;
    }

    private static Mt940Line entry(String value) {
        Matcher matcher = ENTRY.matcher(value);
        if (!matcher.matches()) {
            throw new Mt940FormatException("Unreadable :61: entry: " + value);
        }
        LocalDate valueDate = date(matcher.group(1));
        LocalDate bookingDate = bookingDate(valueDate, matcher.group(2));
        BigDecimal amount = amount(matcher.group(5));
        Mt940Mark mark = matcher.group(3).endsWith("C") ? Mt940Mark.C : Mt940Mark.D;

        String references = matcher.group(7);
        int separator = references.indexOf("//");
        String customerReference = (separator < 0 ? references : references.substring(0, separator)).strip();
        String bankReference = separator < 0 ? null : references.substring(separator + 2).strip();

        return new Mt940Line(bookingDate, valueDate, amount, mark, bankReference, customerReference, "", null, null);
    }

    private static Mt940Line information(Mt940Line line, String value) {
        if (!value.contains("~")) {
            return withInformation(line, value.strip(), null, null);
        }
        StringBuilder remittance = new StringBuilder();
        String name = null;
        String iban = null;
        Matcher matcher = SUBFIELD.matcher(value);
        while (matcher.find()) {
            int subfield = Integer.parseInt(matcher.group(1));
            String content = matcher.group(2);
            if (subfield >= 20 && subfield <= 29) {
                remittance.append(content);
            } else if (subfield == 32 || subfield == 33) {
                name = name == null ? content : name + content;
            } else if (subfield == 38) {
                iban = content;
            }
        }
        return withInformation(line, remittance.toString().strip(), blankToNull(name), blankToNull(iban));
    }

    private static Mt940Line withInformation(Mt940Line line, String remittance, String name, String iban) {
        return new Mt940Line(line.bookingDate(), line.valueDate(), line.amount(), line.mark(),
            line.bankReference(), line.customerReference(), remittance, name, iban);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static LocalDate date(String yymmdd) {
        try {
            return LocalDate.parse(yymmdd, YYMMDD);
        } catch (DateTimeParseException e) {
            throw new Mt940FormatException("Unreadable date: " + yymmdd);
        }
    }

    /**
     * The booking date carries no year. It is the value date's year, moved by one when the two fall
     * on opposite sides of New Year — a January statement carries December bookings.
     */
    private static LocalDate bookingDate(LocalDate valueDate, String mmdd) {
        if (mmdd == null) {
            return valueDate;
        }
        int month = Integer.parseInt(mmdd.substring(0, 2));
        int day = Integer.parseInt(mmdd.substring(2, 4));
        LocalDate candidate = LocalDate.of(valueDate.getYear(), month, day);
        if (candidate.isAfter(valueDate.plusMonths(6))) {
            return candidate.minusYears(1);
        }
        if (candidate.isBefore(valueDate.minusMonths(6))) {
            return candidate.plusYears(1);
        }
        return candidate;
    }

    private static BigDecimal amount(String text) {
        try {
            return new BigDecimal(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new Mt940FormatException("Unreadable amount: " + text);
        }
    }

    /** :60F: is mark + YYMMDD + currency + amount; only the currency matters here. */
    private static String balanceCurrency(String value) {
        if (value.length() < 10) {
            throw new Mt940FormatException("Unreadable opening balance: " + value);
        }
        return value.substring(7, 10);
    }
}
