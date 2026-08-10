package pl.najem.app.web;

/**
 * Polish has three plural forms, not two, and the rule is on the last two digits.
 *
 * <p>One (1 lokal), few for a count ending in 2–4 (2 lokale, 23 lokale) — except the teens, where
 * 12–14 take the many form (12 lokali, not "12 lokale") — and many for everything else (5 lokali,
 * 0 lokali). English's singular/plural applied here reads as broken Polish on most counts rather
 * than on an edge case, and the teens exception is the half of the rule that gets left out;
 * {@code PolishPluralTest} pins both.
 *
 * <p>Its own class rather than a static on the first screen that needed it. It was package-private
 * on {@code PropertiesScreenController}, and the second caller would have had to reach into a
 * controller for a language rule that has nothing to do with properties — at which point the third
 * caller copies it instead, and the teens exception is what gets dropped in the copy.
 */
final class PolishPlural {

    private PolishPlural() {
    }

    static String of(long count, String one, String few, String many) {
        long lastTwo = Math.abs(count) % 100;
        long last = Math.abs(count) % 10;
        if (count == 1) {
            return one;
        }
        if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) {
            return few;
        }
        return many;
    }

    /** {@code "12 lokali"} — the count and its form together, which is how every caller wants it. */
    static String count(long count, String one, String few, String many) {
        return count + " " + of(count, one, few, many);
    }
}
