package pl.najem.app.web;

import java.util.List;

/**
 * The unit screen's five tabs, invented.
 *
 * <p><b>Nothing here is read from anywhere.</b> Every name, date, reading and amount below is a
 * literal, exactly as {@link DashboardFake} — this class exists so the screen prototype v2 draws
 * can be seen, navigated and reviewed in the running application before the read sides it needs
 * exist. It is the same bargain that file records: a later query returning these shapes replaces
 * this file without touching a line of markup.
 *
 * <p><b>What is NOT here is as deliberate as what is.</b> The unit's own name, its market state,
 * its base rent, who is interested in it and who its current tenant is are all real, read from
 * Reporting, Contacts and PM by {@link UnitScreenController}. This file covers only the widgets
 * whose read side does not exist yet: the four Oś czasu views, the attention and upcoming rails,
 * the ledger, the meters and the documents. A widget that could be filled from a real port and is
 * filled from here instead would be a lie that survives the port arriving.
 *
 * <p><b>The figures agree with each other, deliberately</b> — the same rule DashboardFake states
 * and for the same reason. The three history spells sum to the 22% / 34% / 34% occupancy band with
 * two vacancy gaps of 6% and 4%; 3 300 → 3 550 → 3 700 is +7.6% then +4.2%, which is what the rent
 * steps claim; the ledger's running balance actually runs. A mockup whose numbers contradict each
 * other teaches a reviewer to stop reading them.
 *
 * <p>Tones are this application's own vocabulary — {@code green}, {@code warn}, {@code danger},
 * {@code neutral} — never a hex. The prototype's colours were translated once, here, because a
 * template carrying one is a defect {@code TemplateHygieneTest} fails the build over.
 */
final class UnitDetailFake {

    private UnitDetailFake() {
    }

    // ----------------------------------------------------------------------------------------
    // Oś czasu — four views of the same unit over time
    // ----------------------------------------------------------------------------------------

    /**
     * One segment of the occupancy band. {@code pct} is a share of the whole drawn period, so the
     * segments sum to 100 — the band is a proportion of time, not a bar chart of amounts.
     */
    record Band(String label, String tone, double pct) {
    }

    /** One past or present tenancy, as the cards under the band. */
    record Spell(String name, String range, String rent, String months, String late, String note,
                 boolean flagged) {
    }

    /** One month of the payment strip. {@code marker} is days late, {@code !} unpaid, blank on time. */
    record PayMonth(String month, String marker, String tone) {
    }

    /** One step of the rent history. {@code pct} is the bar's height as a share of the tallest. */
    record RentStep(String label, String amount, double pct, String tone) {
    }

    /** One dated entry of a rail — maintenance behind, or what is coming. */
    record Event(String title, String subline, String date, String tone) {
    }

    static final List<Band> BAND = List.of(
        new Band("Robert Mazur", "neutral", 22),
        new Band("", "void", 6),
        new Band("Katarzyna Nowak", "soft", 34),
        new Band("", "void", 4),
        new Band("Obecny najem", "current", 34));

    static final List<String> BAND_AXIS =
        List.of("01.2023", "01.2024", "01.2025", "01.2026", "05.2027");

    static final List<Spell> SPELLS = List.of(
        new Spell("Robert Mazur", "01.2023 – 05.2024", "3 300 zł", "16 miesięcy",
            "1 płatność po terminie",
            "Koniec: wyjazd za granicę · kaucja zwrócona w całości", false),
        new Spell("Katarzyna Nowak", "08.2024 – 04.2026", "3 550 zł", "21 miesięcy",
            "1 płatność po terminie",
            "Koniec: przeprowadzka do 2B · 640 zł potrącone na malowanie", false),
        new Spell("Obecny najem", "06.2026 – trwa", "3 900 zł", "3 miesiące",
            "1 po terminie, 1 niezapłacona",
            "Umowa do 31.05.2027 · najem okazjonalny", true));

    /** Śr. długość najmu / pustostan / wzrost czynszu / koszt zmiany — the band's own footer. */
    static final List<String> BAND_STATS = List.of(
        "Śr. długość najmu — 17 miesięcy",
        "Pustostan od 2023 — 3 miesiące (7%)",
        "Wzrost czynszu — +18% od 2023",
        "Koszt zmiany najemcy — śr. 4 200 zł");

    /**
     * Twelve months back from the current one. The pattern is the prototype's: mostly on time, four
     * months a few days late, and the current one unpaid — which is what the attention rail and the
     * ledger's top row both say, so the three widgets agree.
     */
    static final List<PayMonth> PAY_MONTHS = List.of(
        new PayMonth("W", "", "green"),
        new PayMonth("P", "", "green"),
        new PayMonth("L", "+2", "warn"),
        new PayMonth("G", "", "green"),
        new PayMonth("S", "", "green"),
        new PayMonth("L", "+3", "warn"),
        new PayMonth("M", "", "green"),
        new PayMonth("K", "", "green"),
        new PayMonth("M", "+4", "warn"),
        new PayMonth("C", "", "green"),
        new PayMonth("L", "+6", "warn"),
        new PayMonth("S", "!", "danger"));

    static final String PAY_AVERAGE = "Średnio 3,4 dnia do zapłaty";

    static final List<RentStep> RENT_STEPS = List.of(
        new RentStep("2023", "3 300 zł", 58, "past"),
        new RentStep("2024 · +7,6%", "3 550 zł", 68, "past"),
        new RentStep("2025 · +4,2%", "3 700 zł", 76, "soft"),
        new RentStep("2026 · nowa umowa", "3 900 zł", 88, "current"),
        new RentStep("2027 · prog. CPI", "4 060 zł", 96, "projected"));

    static final String RENT_NOTE = "Następna indeksacja 01.01.2027 wg CPI GUS, limit 8%. "
        + "Zawiadomienie musi dotrzeć do najemcy do 30.11.2026.";

    static final List<Event> MAINTENANCE = List.of(
        new Event("Wymiana pieca", "Termika sp. z o.o. · 6 400 zł · 5 lat gwarancji",
            "14.03.2026", "green"),
        new Event("Malowanie po najmie", "640 zł potrącone z kaucji K. Nowak",
            "02.05.2026", "neutral"),
        new Event("Wymiana uszczelek okien",
            "Zgłoszone przez najemcę · zamknięte w tym samym tygodniu", "18.11.2025", "neutral"),
        new Event("Przegląd gazowy", "Coroczny przegląd ustawowy · wynik pozytywny",
            "09.09.2025", "neutral"));

    // ----------------------------------------------------------------------------------------
    // Przegląd — the two rails beside the contract card
    // ----------------------------------------------------------------------------------------

    /** One row of "Wymaga uwagi". `tone` is `danger` or `warn` and drives the left edge only —
     *  the same contract {@link DashboardFake.Decision} carries on the dashboard. */
    record Attention(String tone, String title, String subline, String action) {
    }

    static final List<Attention> ATTENTION = List.of(
        new Attention("danger", "Czynsz za sierpień niezapłacony — 4 dni",
            "4 010 zł · termin 10.08 · pierwsze ponaglenie 13.08 · brak odpowiedzi",
            "Zarejestruj wpłatę"),
        new Attention("warn", "Nierozliczone media za II kwartał",
            "640 zł nadpłaty na koncie od 02.08 — zaliczyć albo zwrócić", "Zalicz"));

    static final List<Event> UPCOMING = List.of(
        new Event("Otwiera się okno wypowiedzenia",
            "Podstawa do wypowiedzenia, jeśli czynsz za sierpień nie wpłynie",
            "20.08.2026", "danger"),
        new Event("Termin odczytu liczników", "Zimna woda, ciepła woda, gaz · odczyt najemcy",
            "31.08.2026", "neutral"),
        new Event("Wystawienie kolejnej faktury", "FV 09/2026 · 4 010 zł", "01.09.2026", "neutral"),
        new Event("Wchodzi indeksacja CPI", "Prog. +4,1% → 4 060 zł · zawiadomienie do 30.11",
            "01.01.2027", "warn"),
        new Event("Koniec umowy", "Decyzja o przedłużeniu trzy miesiące wcześniej",
            "31.05.2027", "warn"));

    // ----------------------------------------------------------------------------------------
    // Konto — the tenant's ledger
    // ----------------------------------------------------------------------------------------

    /**
     * One ledger line. Charge and payment are strings rather than amounts because a line has one or
     * the other and never both, and an absent one renders as a dash — a {@code BigDecimal.ZERO}
     * there would claim a zero-złoty charge was posted.
     *
     * <p>{@code tone} is the running balance's own: {@code danger} while something is owed,
     * {@code neutral} once it is settled, {@code faint} for the deposit, which is held rather than
     * owed and is not part of the running figure at all.
     */
    record LedgerRow(String date, String description, String charge, String payment,
                     String balance, String tone, boolean flagged) {
    }

    static final List<LedgerRow> LEDGER = List.of(
        new LedgerRow("10.08.2026", "Czynsz + media · FV 08/2026", "4 010,00", null,
            "−4 010,00", "danger", true),
        new LedgerRow("02.08.2026", "Rozliczenie mediów II kw. · nadpłata", null, "640,00",
            "+640,00", "neutral", false),
        new LedgerRow("09.07.2026", "Przelew · mBank …8821", null, "4 010,00",
            "0,00", "neutral", false),
        new LedgerRow("10.07.2026", "Czynsz + media · FV 07/2026", "4 010,00", null,
            "−4 010,00", "danger", false),
        new LedgerRow("01.06.2026", "Wpłata kaucji · na rachunku powierniczym", null, "7 800,00",
            "kaucja", "faint", false),
        new LedgerRow("01.06.2026", "Otwarcie najmu", null, null, "0,00", "neutral", false));

    static final String LEDGER_ACCOUNTS =
        "Księgowane na 201 Należności czynszowe / 700 Przychody z najmu";

    // ----------------------------------------------------------------------------------------
    // Liczniki and Dokumenty — the two tabs the prototype leaves as a placeholder
    // ----------------------------------------------------------------------------------------

    /**
     * One meter. The prototype draws this tab as "poza zakresem prototypu"; the structure below is
     * this application's own reading of what a meters tab is — a serial, the last reading and when
     * the next one is due, which is what the Przegląd rail's "Termin odczytu liczników" implies
     * must exist somewhere.
     */
    record Meter(String kind, String serial, String reading, String unit, String readOn,
                 String dueOn) {
    }

    static final List<Meter> METERS = List.of(
        new Meter("Woda zimna", "WZ-4471-092", "182,4", "m³", "30.06.2026", "31.08.2026"),
        new Meter("Woda ciepła", "WC-4471-093", "96,1", "m³", "30.06.2026", "31.08.2026"),
        new Meter("Gaz", "G-88213-04", "1 204", "m³", "30.06.2026", "31.08.2026"),
        new Meter("Energia elektryczna", "E-55190-11", "8 431", "kWh", "30.06.2026", "30.09.2026"),
        new Meter("Ciepło", "C-2201-77", "14,8", "GJ", "31.05.2026", "31.10.2026"));

    static final String METERS_NOTE = "Odczyt najemcy potwierdza zarządca przy rozliczeniu "
        + "kwartalnym. Różnica powyżej 20% wobec poprzedniego okresu wymaga sprawdzenia.";

    /** One document. `tone` marks a document that is missing rather than filed. */
    record Document(String name, String kind, String added, String size, String tone) {
    }

    static final List<Document> DOCUMENTS = List.of(
        new Document("Umowa najmu okazjonalnego", "Umowa", "01.06.2026", "412 kB", "green"),
        new Document("Oświadczenie o poddaniu się egzekucji", "Akt notarialny", "01.06.2026",
            "1,1 MB", "green"),
        new Document("Wskazanie lokalu zastępczego", "Oświadczenie", "01.06.2026", "208 kB",
            "green"),
        new Document("Protokół zdawczo-odbiorczy", "Protokół", "01.06.2026", "3,4 MB", "green"),
        new Document("Potwierdzenie wpłaty kaucji", "Bankowy", "01.06.2026", "96 kB", "green"),
        new Document("Aneks indeksacyjny 2027", "Aneks", null, null, "warn"));

    static final String DOCUMENTS_NOTE = "Aneks indeksacyjny musi zostać podpisany i dołączony "
        + "przed 30.11.2026, żeby indeksacja od 01.01.2027 była skuteczna.";

    /**
     * The whole invented half of the screen, as one model attribute.
     *
     * <p>{@link DashboardFake.View} makes the argument and it holds here for the same reason: a
     * dozen loose attribute names is a dozen names the controller has to get right, and a missing
     * one renders as a blank card rather than as an error. One object also makes the swap visible
     * in the type — the day the ledger is real, {@code ledger} comes off this record and the
     * template's loop does not change.
     */
    record View(List<Band> band, List<String> bandAxis, List<Spell> spells, List<String> bandStats,
                List<PayMonth> payMonths, String payAverage, List<RentStep> rentSteps,
                String rentNote, List<Event> maintenance, List<Attention> attention,
                List<Event> upcoming, List<LedgerRow> ledger, String ledgerAccounts,
                List<Meter> meters, String metersNote, List<Document> documents,
                String documentsNote) {
    }

    static final View VIEW = new View(BAND, BAND_AXIS, SPELLS, BAND_STATS, PAY_MONTHS, PAY_AVERAGE,
        RENT_STEPS, RENT_NOTE, MAINTENANCE, ATTENTION, UPCOMING, LEDGER, LEDGER_ACCOUNTS,
        METERS, METERS_NOTE, DOCUMENTS, DOCUMENTS_NOTE);
}
