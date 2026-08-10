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

    /**
     * One tick under the band: a dated event, positioned as a percentage of the same window.
     *
     * <p><b>The window is what makes this work.</b> Prototype v2's own note says the band, the ticks
     * and the axis are all computed from one time window derived from the tenancy's real dates —
     * before that they were three independent drawings that agreed on one unit and disagreed on
     * every other. Here the whole card is invented, so "one window" means one set of literals: the
     * percentages below are cumulative sums of {@link #BAND}'s own widths, which is what keeps a
     * tick under the segment boundary it marks.
     *
     * @param pct   distance from the left edge, 0–100, on the same scale as {@link Band#pct()}.
     * @param label empty for the closing half of a pair. Two ticks two months apart on a
     *              four-year window cannot both carry text without colliding, so the boundary that
     *              matters is labelled and its partner is a bare tick with a {@code title}.
     */
    record Tick(double pct, String label, String title, String tone) {
    }

    /**
     * The five boundaries of {@link #BAND}, and the reservation before the current tenancy.
     *
     * <p>Positions are the running totals of the band's own widths — 22, 28, 62, 66 — so a tick sits
     * exactly where the segment it names begins or ends. Written as literals rather than summed in
     * Java on purpose: this is a drawing, and a reader checking it against the band above should be
     * able to add four numbers rather than run the code.
     */
    static final List<Tick> BAND_EVENTS = List.of(
        new Tick(0, "Start Mazur", "Start najmu — Robert Mazur", "neutral"),
        new Tick(22, "", "Koniec najmu — Robert Mazur", "neutral"),
        new Tick(28, "Start Nowak", "Start najmu — Katarzyna Nowak", "neutral"),
        new Tick(62, "", "Koniec najmu — Katarzyna Nowak", "neutral"),
        new Tick(66, "Start obecnego", "Start bieżącego najmu", "green"),
        new Tick(100, "Koniec umowy", "Koniec bieżącej umowy", "warn"));

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
     * One reading that is coming, on the rail above the meter cards.
     *
     * <p>Sorted by date here, in the literal, rather than at render time: the list is a drawing and
     * a sort in Java over invented dates is machinery that proves nothing. Prototype v2 asks for
     * "sortowany od najbliższej daty" and this is that order, written down.
     */
    record Due(String title, String subline, String date, String tone) {
    }

    static final List<Due> METER_DUE = List.of(
        new Due("Woda — odczyt radiowy", "MPWiK, zdalnie · bez udziału najemcy", "15.09.2026",
            "neutral"),
        new Due("Prąd — odczyt rozliczeniowy", "Tauron, cykl dwumiesięczny", "30.09.2026", "warn"),
        new Due("Gaz — przegląd instalacji", "Wymóg roczny", "12.11.2026", "neutral"),
        new Due("Ciepłomierz — legalizacja", "Wymiana modułu przed upływem terminu", "31.12.2026",
            "danger"));

    /**
     * One meter, as its own card.
     *
     * <p>Prototype v2 turns the flat table into a card per meter, and the reason is the content
     * rather than the layout: a meter carries a current reading, a monthly consumption, a serial and
     * a legalisation date, and five columns of that is a table nobody reads across. The
     * {@code state} is what a manager acts on and it is the only thing on the card with a tone.
     *
     * @param legalisedUntil when the meter's legalisation expires. A meter past it may not be used
     *                       to settle anything, which is why it is on the card rather than in a
     *                       register somewhere — {@code Ciepłomierz} below is the case that shows
     *                       why the date needs a tone of its own.
     */
    record Meter(String kind, String serial, String reading, String unit, String monthly,
                 String readOn, String legalisedUntil, boolean legalisationDue,
                 String state, String stateTone) {
    }

    static final List<Meter> METERS = List.of(
        new Meter("Prąd", "EL-88214773", "14 208", "kWh", "312 kWh", "01.08.2026", "05.2031",
            false, "Aktualny", "paid"),
        new Meter("Woda zimna", "WZ-40021188", "286,4", "m³", "4,1 m³", "01.08.2026", "11.2029",
            false, "Aktualny", "paid"),
        new Meter("Woda ciepła", "WC-40021189", "141,8", "m³", "2,6 m³", "01.08.2026", "11.2029",
            false, "Aktualny", "paid"),
        new Meter("Gaz", "GZ-77310042", "1 942", "m³", "18 m³", "01.07.2026", "08.2030",
            false, "Odczyt zaległy", "warn"),
        new Meter("Ciepłomierz", "CP-19022204", "38,42", "GJ", "0,9 GJ", "01.08.2026", "12.2026",
            true, "Legalizacja wygasa", "danger"));

    /**
     * One historical reading. Newest first, and the source is on every row.
     *
     * <p><b>The source is the point of this table, not the number.</b> A reading a tenant
     * photographed, a reading a radio module reported and a reading taken off a handover protocol
     * carry different weight in a dispute, and a settlement built on the first of those has to be
     * traceable to the photograph. That is why {@code photo} is a flag rather than a decoration.
     */
    record Reading(String date, String meter, String value, String consumption, String source,
                   boolean photo) {
    }

    static final List<Reading> METER_READINGS = List.of(
        new Reading("01.08.2026", "Prąd", "14 208 kWh", "+312 kWh", "zdjęcie najemcy", true),
        new Reading("01.08.2026", "Woda zimna", "286,4 m³", "+4,1 m³", "odczyt radiowy", false),
        new Reading("01.08.2026", "Woda ciepła", "141,8 m³", "+2,6 m³", "odczyt radiowy", false),
        new Reading("01.08.2026", "Ciepłomierz", "38,42 GJ", "+0,9 GJ", "odczyt radiowy", false),
        new Reading("01.07.2026", "Prąd", "13 896 kWh", "+298 kWh", "zdjęcie najemcy", true),
        new Reading("01.07.2026", "Gaz", "1 942 m³", "+18 m³", "odczyt zarządcy", true),
        new Reading("01.07.2026", "Woda zimna", "282,3 m³", "+3,8 m³", "odczyt radiowy", false),
        new Reading("01.06.2026", "Prąd", "13 598 kWh", "+341 kWh", "protokół zdawczy", true),
        new Reading("01.06.2026", "Woda zimna", "278,5 m³", "+4,4 m³", "protokół zdawczy", true));

    static final String METERS_NOTE = "Liczniki konfiguruje się per lokal — prąd i woda domyślnie, "
        + "gaz i ciepło, gdy jest instalacja, dowolny podlicznik dodatkowo. Odczyt najemcy "
        + "potwierdza zarządca przy rozliczeniu; różnica powyżej 20% wobec poprzedniego okresu "
        + "wymaga sprawdzenia.";

    // ----------------------------------------------------------------------------------------
    // Dokumenty — the unit's own papers, which are not the contract's
    // ----------------------------------------------------------------------------------------

    /**
     * One document belonging to the UNIT.
     *
     * <p><b>The split from the contract's documents is the change prototype v2 makes here, and it is
     * a real distinction rather than a filing convention.</b> An umowa, a protokół zdawczo-odbiorczy
     * and an aneks belong to a tenancy: they name parties, they expire with the contract, and a new
     * tenant gets new ones. A przegląd kominiarski, a świadectwo energetyczne and a rzut belong to
     * the unit: they survive every tenant, and a manager chasing an expiring one is not thinking
     * about who lives there. This tab now holds the second kind and points at the first.
     *
     * @param validUntil null for a document with no expiry — a floor plan does not go out of date.
     *                   Null rather than a far-future date, so "no deadline" and "a deadline in
     *                   2099" stay different answers.
     * @param state      Aktualny / Wygasa / Wygasł / Bez terminu, with the tone that goes with it.
     */
    record Document(String name, String detail, String kind, String issued, String validUntil,
                    String state, String tone) {
    }

    /**
     * Newest issue date first, which is the order prototype v2 asks for and the order a filing
     * cabinet is read in. Written in that order rather than sorted at render time, for the reason
     * {@link #METER_DUE} gives: sorting invented dates in Java is machinery that proves nothing.
     */
    static final List<Document> DOCUMENTS = List.of(
        new Document("Przegląd instalacji gazowej", "Gaz-Serwis Warszawa · bez uwag", "Przeglądy",
            "12.11.2025", "12.11.2026", "Aktualny", "paid"),
        new Document("Przegląd kominiarski", "Kominy Mazowsze · przewód spalinowy sprawny",
            "Przeglądy", "24.09.2025", "24.09.2026", "Wygasa", "warn"),
        new Document("Protokół pomiarów elektrycznych", "Pomiary rezystancji izolacji", "Przeglądy",
            "18.05.2024", "18.05.2029", "Aktualny", "paid"),
        new Document("Gwarancja kotła Vaillant", "Nr seryjny 21-4408812", "Gwarancje",
            "02.02.2024", "02.02.2029", "Aktualny", "paid"),
        new Document("Zdjęcia lokalu po remoncie", "24 zdjęcia · stan zerowy", "Zdjęcia",
            "20.03.2023", null, "Bez terminu", "neutral"),
        new Document("Rzut lokalu po remoncie", "Układ ścian i instalacji, 2023", "Plany",
            "14.03.2023", null, "Bez terminu", "neutral"),
        new Document("Świadectwo energetyczne", "Klasa D · 168 kWh/m² rocznie", "Świadectwa",
            "30.06.2016", "30.06.2026", "Wygasł", "danger"));

    /** The two documents whose dates a manager has to act on, called out above the list — an expiry
     *  buried in a seven-row table sorted by ISSUE date is an expiry nobody sees. */
    static final List<Due> DOCUMENT_ALERTS = List.of(
        new Due("Świadectwo energetyczne", "Wygasło — wymagane przy zawarciu najmu", "30.06.2026",
            "danger"),
        new Due("Przegląd kominiarski", "Wygasa za 41 dni", "24.09.2026", "warn"));

    /**
     * Deliberately NOT a second copy of the bar above the list.
     *
     * <p>Both said "umowa, protokoły i aneksy należą do najmu" — the same sentence twice on one
     * screen, which was obvious the moment the tab was loaded in a browser and invisible in the
     * test that asserts each string is present. The bar carries the split and the link to the
     * contract; this says the thing the LIST needs saying about it.
     */
    static final String DOCUMENTS_NOTE = "Termin ważności liczy się od daty wystawienia. Dokument "
        + "bez terminu — rzut, zdjęcia — zostaje przy lokalu, dopóki nie zmieni się jego stan.";

    /**
     * The whole invented half of the screen, as one model attribute.
     *
     * <p>{@link DashboardFake.View} makes the argument and it holds here for the same reason: a
     * dozen loose attribute names is a dozen names the controller has to get right, and a missing
     * one renders as a blank card rather than as an error. One object also makes the swap visible
     * in the type — the day the ledger is real, {@code ledger} comes off this record and the
     * template's loop does not change.
     */
    record View(List<Band> band, List<String> bandAxis, List<Tick> bandEvents, List<Spell> spells,
                List<String> bandStats, List<PayMonth> payMonths, String payAverage,
                List<RentStep> rentSteps, String rentNote, List<Event> maintenance,
                List<Attention> attention, List<Event> upcoming, List<LedgerRow> ledger,
                String ledgerAccounts, List<Due> meterDue, List<Meter> meters,
                List<Reading> meterReadings, String metersNote, List<Due> documentAlerts,
                List<Document> documents, String documentsNote) {
    }

    static final View VIEW = new View(BAND, BAND_AXIS, BAND_EVENTS, SPELLS, BAND_STATS, PAY_MONTHS,
        PAY_AVERAGE, RENT_STEPS, RENT_NOTE, MAINTENANCE, ATTENTION, UPCOMING, LEDGER,
        LEDGER_ACCOUNTS, METER_DUE, METERS, METER_READINGS, METERS_NOTE, DOCUMENT_ALERTS, DOCUMENTS,
        DOCUMENTS_NOTE);
}
