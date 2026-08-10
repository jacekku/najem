package pl.najem.app.web;

import java.util.List;
import java.util.Map;

/**
 * The Pulpit screen's contents, invented.
 *
 * <p><b>Nothing here is read from anywhere.</b> Every name, address, date and amount below is a
 * literal, and no module is consulted to produce any of them — this class exists so the screen
 * prototype v2 draws can be seen, navigated and reviewed in the running application before the
 * five read sides it needs exist.
 *
 * <p>It is a class rather than literals in the template for one reason: the swap to real data
 * should be a change to what fills these records, and to nothing else. The template already
 * iterates lists and renders the real component fragments, so a later {@code DashboardQuery}
 * returning the same shapes replaces this file without touching a line of markup.
 *
 * <p><b>The figures agree with each other, deliberately.</b> 14 700 + 11 710 + 19 510 = 45 920,
 * which is the "Naliczono" KPI; 14 700 / 45 920 = 32%, which is the "Wpłacono" caption; the three
 * bar segments are those three amounts as percentages of that total. A mockup whose numbers
 * contradict each other teaches a reviewer to stop reading them, and the arithmetic is the part of
 * a finance screen most worth reviewing.
 *
 * <p>Static, and called from both {@code /} and {@code /workspace}: those two routes are one screen
 * (see {@link WorkspaceScreenController}), and two copies of this data would drift.
 */
final class DashboardFake {

    private DashboardFake() {
    }

    /** One row of "Wymaga decyzji" — `tone` is `danger` or `warn` and drives the left edge only. */
    record Decision(String tone, String title, String subline, String action, String href) {
    }

    /** One row of the unpaid table. `tone`/`status` are the pill; the colour never carries it alone. */
    record Unpaid(String initials, String name, String unit, String due, String amount,
                  String tone, String status) {
    }

    /** One KPI tile. `tone` is `''`, `green` or `danger` — the kpi fragment's own vocabulary. */
    record Kpi(String label, String value, String caption, String tone) {
    }

    /** One transfer that landed today. `tone` is the dot: `green`, `warn` or `neutral`. */
    record Payment(String tone, String name, String subline, String amount) {
    }

    /** A name and what they owe, for the unpaid table's footer. */
    record Debtor(String name, String amount) {
    }

    /** One key under the collection bar — the same three tones, in the same order as the segments. */
    record Legend(String tone, String label, String amount) {
    }

    static final String PERIOD = "sierpień 2026";

    static final String URGENT = "3 pilne";

    static final List<Decision> DECISIONS = List.of(
        new Decision("danger", "3 niedopasowane przelewy",
            "11 240 zł na koncie rozliczeniowym od 08.08", "Rozlicz ›", "/bank"),
        new Decision("danger", "R. Nowicki — czas na drugie wezwanie",
            "5 240 zł · okno wypowiedzenia od 20.08", "Otwórz ›", "/reports"),
        new Decision("danger", "Niedobór kaucji · Wilanówka A/8",
            "Kaucja 8 020 zł wobec 8 400 zł z umowy", "Otwórz ›", "/properties"),
        new Decision("warn", "2 umów kończy się w 90 dni",
            "Najbliższa: Hoża 42 · 1A, 30.09.2026", "Przejrzyj ›", "/properties"),
        new Decision("warn", "Sierpniowa indeksacja czynszu",
            "11 umów indeksowanych CPI · jeszcze niezastosowana", "Przejrzyj ›", "/properties"));

    static final String OVERDUE = "5 zaległych";

    static final List<Unpaid> UNPAID = List.of(
        new Unpaid("AK", "Anna Kowalska", "Hoża 42 · 1A", "10.08.2026", "4 120 zł",
            "danger", "4 dni zwłoki"),
        new Unpaid("PZ", "Piotr Zieliński", "Hoża 42 · 2A", "10.08.2026", "4 540 zł",
            "danger", "4 dni zwłoki"),
        new Unpaid("BK", "Barbara Krawczyk", "Wilanówka · A/8", "10.08.2026", "4 010 zł",
            "danger", "4 dni zwłoki"),
        new Unpaid("RN", "Robert Nowicki", "Wilanówka · B/2", "10.08.2026", "5 240 zł",
            "danger", "4 dni zwłoki"),
        new Unpaid("MW", "Michał Wójcik", "Wilanówka · C/7", "15.08.2026", "4 480 zł",
            "neutral", "Termin 15.08"),
        new Unpaid("MW", "Magdalena Woźniak", "Piotrkowska 76 · 3.02", "15.08.2026", "3 750 zł",
            "neutral", "Termin 15.08"),
        new Unpaid("GJ", "Grzegorz Jankowski", "Piotrkowska 76 · 2.01", "15.08.2026", "3 480 zł",
            "neutral", "Termin 15.08"),
        new Unpaid("EK", "Ewa Kamińska", "Piotrkowska 76 · 3.05", "10.08.2026", "1 600 zł",
            "warn", "Częściowo"));

    static final String UNPAID_COUNT = "8 nieopłaconych z 11 najmów";

    static final List<Debtor> DEBTORS = List.of(
        new Debtor("Robert Nowicki", "5 240 zł"),
        new Debtor("Piotr Zieliński", "4 540 zł"),
        new Debtor("Anna Kowalska", "4 120 zł"));

    static final List<Kpi> KPIS = List.of(
        new Kpi("Naliczono — sierpień", "45 920 zł", "11 faktur · 11 najmów", ""),
        new Kpi("Wpłacono", "14 700 zł", "32% naliczenia", "green"),
        new Kpi("Zaległości", "19 510 zł", "5 najmów · śr. 4 dni zwłoki", "danger"),
        new Kpi("Zajętość", "91.7%", "11 z 12 lokali · 1 pustych", ""));

    static final String PAID_TODAY = "10 900 zł";

    static final List<Payment> PAYMENTS = List.of(
        new Payment("green", "Katarzyna Nowak", "Hoża 42 · 2B · 09:14 · dopasowane", "3 400 zł"),
        new Payment("green", "Agnieszka Dąbrowska", "Wilanówka · A/3 · 08:52", "5 100 zł"),
        new Payment("warn", "Ewa Kamińska", "Częściowa · 1 600 z 3 200 zł", "1 600 zł"),
        new Payment("neutral", "Nierozpoznany przelew",
            "Tytuł: „czynsz sierpień” · brak dopasowania", "1 900 zł"));

    static final String CYCLE = "stan na 14.08.2026 · 17 dni cyklu";

    static final String REMAINING = "pozostaje 31 220 zł";

    /**
     * The bar's segments, as maps rather than a record: {@code progressBar} reads
     * {@code segment.pct} and {@code segment.tone} as SpEL properties, which is what every existing
     * caller (design.html) passes it. Percentages of 45 920 — 32.0 / 25.5 / 42.5, summing to 100.
     */
    static final List<Map<String, Object>> SEGMENTS = List.of(
        Map.of("pct", 32.0, "tone", "green"),
        Map.of("pct", 25.5, "tone", "warn"),
        Map.of("pct", 42.5, "tone", "danger"));

    static final List<Legend> LEGEND = List.of(
        new Legend("green", "Zapłacone", "14 700 zł"),
        new Legend("warn", "Termin nie minął", "11 710 zł"),
        new Legend("danger", "Zaległe", "19 510 zł"));

    /**
     * The whole screen, as one model attribute.
     *
     * <p>Fourteen separate attributes was fourteen names two controllers each had to get right, and
     * a missing one renders as a blank card rather than as an error. One object also makes the swap
     * to real data visible in the type: a {@code DashboardQuery} returning this record is the whole
     * change.
     */
    record View(String period, String urgent, List<Decision> decisions,
                String overdue, List<Unpaid> unpaid, String unpaidCount, List<Debtor> debtors,
                List<Kpi> kpis, String paidToday, List<Payment> payments,
                String cycle, String remaining, List<Map<String, Object>> segments,
                List<Legend> legend) {
    }

    static final View VIEW = new View(PERIOD, URGENT, DECISIONS, OVERDUE, UNPAID, UNPAID_COUNT,
        DEBTORS, KPIS, PAID_TODAY, PAYMENTS, CYCLE, REMAINING, SEGMENTS, LEGEND);
}
