package pl.najem.app.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A suggested transfer title, which the manager may overwrite.
 *
 * <p><b>It is not unique and does not try to be.</b> Two units named {@code m. 2} in different
 * properties, starting the same month, produce the same string — and reconciliation matches bank
 * lines on it, so a duplicate means one transfer matches two tenancies. {@code UnitBoardQuery.Row}
 * carries no address to discriminate with, and no port exists to ask accounting whether a reference
 * is already taken. Written down here rather than left to be discovered.
 *
 * <p>A suggestion and not a generated value, so an agency migrating tenancies can enter the
 * reference their tenants already quote.
 */
public final class PaymentReferences {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /** What a unit whose name is entirely punctuation falls back to, rather than an empty segment. */
    private static final String UNNAMED = "LOKAL";

    private PaymentReferences() {
    }

    /**
     * The sanitiser drops Polish diacritics along with punctuation, because {@code Ł} is not in
     * {@code A-Z}. That is intended: a transfer title is typed by a tenant into a bank form, and an
     * ASCII one survives every bank's field.
     */
    public static String suggest(String unitName, LocalDate startDate) {
        String sanitised = unitName == null ? "" : unitName.toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]", "");
        return "NAJEM/" + (sanitised.isEmpty() ? UNNAMED : sanitised)
            + "/" + MONTH.format(startDate);
    }
}
