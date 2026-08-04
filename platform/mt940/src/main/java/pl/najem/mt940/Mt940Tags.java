package pl.najem.mt940;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits MT940 text into its fields.
 *
 * <p>Lexing only — this class knows that a field starts with {@code :nn:} and that anything else
 * continues the field above. It does not know what any tag means.
 */
public final class Mt940Tags {

    public record Field(String tag, String value) {}

    /** A tag is two digits and an optional letter: :20:, :28C:, :60F:, :61:, :86:. */
    private static final Pattern TAG = Pattern.compile("^:(\\d{2}[A-Z]?):(.*)$");

    /** End-of-block marker; a line of a single hyphen. */
    private static final String BLOCK_TERMINATOR = "-";

    private Mt940Tags() {
    }

    public static List<Field> split(String text) {
        List<String> tags = new ArrayList<>();
        List<StringBuilder> values = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (line.isBlank() || line.strip().equals(BLOCK_TERMINATOR)) {
                continue;
            }
            Matcher matcher = TAG.matcher(line.strip());
            if (matcher.matches()) {
                tags.add(matcher.group(1));
                values.add(new StringBuilder(matcher.group(2)));
            } else if (values.isEmpty()) {
                throw new Mt940FormatException("Expected a field to start the statement, found: " + line);
            } else {
                values.getLast().append(line.strip());
            }
        }
        List<Field> fields = new ArrayList<>(tags.size());
        for (int i = 0; i < tags.size(); i++) {
            fields.add(new Field(tags.get(i), values.get(i).toString()));
        }
        return fields;
    }
}
