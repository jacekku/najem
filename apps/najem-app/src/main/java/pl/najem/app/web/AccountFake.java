package pl.najem.app.web;

import java.util.List;

/**
 * The invented halves of Mój profil and Ustawienia agencji.
 *
 * <p><b>Nothing here is read from anywhere.</b> Same bargain {@link DashboardFake} and
 * {@link UnitDetailFake} record: every value below is a literal, so the two screens prototype v2
 * draws can be seen and navigated in the running application before the read sides they need exist.
 * A later port returning these shapes replaces this file without touching a line of markup.
 *
 * <p><b>What is NOT here is the point.</b> The agency's name, the acting person's name, e-mail and
 * phone, every member of the team and every member's role are all REAL — read from usermanagement
 * and contacts by {@link ProfileScreenController} and {@link AgencySettingsScreenController}. This
 * file covers only what the application genuinely does not store: an agency's tax identity, its
 * settlement defaults, its outbound integrations, and a person's security and notification
 * preferences.
 *
 * <p><b>The split matters more on these two screens than anywhere else.</b> A fake NIP beside a real
 * agency name reads as a real NIP, and somebody will use it on an invoice. So the templates flag
 * every invented card with {@code demo-flag}, exactly as the unit screen's widgets are flagged, and
 * no card mixes an invented field into a real list — the team's names are real or absent, never
 * filled in from here.
 *
 * <p><b>The indexation limit is here rather than in a settings knob, deliberately.</b> Prototype v2
 * exposes {@code cpiCap} on its Tweaks panel; that panel does not come across (it is design-tool
 * scaffolding), and making the limit a real, persisted agency policy is a change to what the
 * application promises about rent — an indexation limit that a screen can edit is one a court can
 * be shown. Until there is a command and a stream behind it, the figure is a literal and says so.
 */
final class AccountFake {

    private AccountFake() {
    }

    /** The one figure two screens share: Ustawienia states the policy, the tenancy screen projects
     *  from it. A literal in one place so the two cannot disagree about what the limit is. */
    static final String CPI_CAP = "8%";

    // ----------------------------------------------------------------------------------------
    // Mój profil
    // ----------------------------------------------------------------------------------------

    /** One preference row. {@code on} is what the checkbox shows; nothing reads it back. */
    record Preference(String label, String detail, boolean on) {
    }

    static final List<Preference> NOTIFICATIONS = List.of(
        new Preference("Nowa zaległość powyżej 7 dni",
            "Wiadomość w dniu, w którym najem przekracza próg", true),
        new Preference("Wyciąg bankowy z niedopasowanymi wpłatami",
            "Po każdym imporcie MT940, jeśli coś zostało nierozliczone", true),
        new Preference("Tygodniowe podsumowanie w poniedziałki",
            "Zaległości, kończące się umowy i terminy na najbliższy tydzień", false));

    static final String TWO_FACTOR_STATE = "Włączone";

    static final String TWO_FACTOR_NOTE = "Drugi składnik logowania ustawia się w Keycloak, "
        + "który jest tu jedynym źródłem prawdy o poświadczeniach. Ten ekran go pokazuje, "
        + "a nie przechowuje.";

    static final String PASSWORD_NOTE = "Hasło zmienia się w Keycloak. NAJEM nigdy go nie widzi "
        + "i nie ma gdzie go zapisać.";

    // ----------------------------------------------------------------------------------------
    // Ustawienia agencji
    // ----------------------------------------------------------------------------------------

    /** One labelled fact on a settings card. {@code mono} is for anything a person copies. */
    record Entry(String label, String value, boolean mono) {
    }

    static final List<Entry> IDENTITY = List.of(
        new Entry("NIP", "525-274-11-08", true),
        new Entry("REGON", "146 118 340", true),
        new Entry("Adres", "ul. Marszałkowska 84/92, 00-514 Warszawa", false),
        new Entry("Rachunek rozliczeniowy", "PL 61 1090 1014 0000 0712 1981 4471", true));

    /**
     * The settlement defaults a new tenancy would inherit.
     *
     * <p>Every one of these is a decision the application already makes somewhere — the payment day
     * on the reservation form, the settlement order in {@code AllocationService} — but none of them
     * is stored per agency, so none can be read back. Showing them as an agency policy is the
     * prototype's claim, not this application's, and the card is flagged accordingly.
     */
    static final List<Entry> BILLING = List.of(
        new Entry("Domyślna prowizja", "8% od wpłat", false),
        new Entry("Dzień wystawiania faktur", "1. dnia miesiąca", false),
        new Entry("Termin płatności", "10 dni", false),
        new Entry("Odsetki za zwłokę", "ustawowe 11,25%", false),
        new Entry("Limit indeksacji", CPI_CAP + " rocznie", false),
        new Entry("Ponaglenie automatyczne", "po 7 dniach zwłoki", false));

    /**
     * One outbound integration.
     *
     * <p>{@code tone} is a {@code pill} tone from this application's own vocabulary — {@code paid},
     * {@code warn}, {@code danger}, {@code neutral}, {@code critical} — and never a hex, which
     * {@code TemplateHygieneTest} fails the build over. {@code paid} rather than a "green" of its
     * own: the pill set has one green and inventing a second name for it is how a tone that does
     * not exist reaches a template and renders as an unstyled pill with no error to say so.
     */
    record Integration(String name, String detail, String state, String tone) {
    }

    static final List<Integration> INTEGRATIONS = List.of(
        new Integration("Import wyciągów MT940", "mBank · codziennie o 6:00", "Aktywna", "paid"),
        new Integration("Wysyłka e-mail", "faktury@agencja.pl", "Aktywna", "paid"),
        new Integration("KSeF", "Krajowy System e-Faktur", "Nieaktywna", "neutral"));
}
