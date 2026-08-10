package pl.najem.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import pl.najem.acc.application.ArrearsBoardProjection;
import pl.najem.acc.application.DepositRepository;
import pl.najem.acc.application.InvoiceRepository;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.pm.application.TenancyBoardProjection;
import pl.najem.pm.application.TenancyDetailRow;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.Tenancy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One contract, as a screen rather than as a row in a register.
 *
 * <p><b>Why this is separate from the unit screen at all.</b> A unit and the tenancy running in it
 * are two subjects, and the unit screen had been answering for both — its "Bieżąca umowa" card
 * carried the term, the components and the deposit, which are facts about a CONTRACT that outlive
 * the tenant being in the unit. A unit has many tenancies over its life; asking "what did we sign"
 * on a screen keyed by unit means the answer changes when somebody moves out, and an ended contract
 * becomes unreachable. Prototype v2 draws the split and this follows it.
 *
 * <p><b>Almost everything here is real, which is unusual for a v2 screen and worth saying.</b> The
 * term, the legal form, the component split, the payment day, the parties, the payment reference
 * and the state come from PM; the names from Contacts; the balance, the arrears colour and — the
 * one that makes the deposit card work — the agreed deposit and how much of it is still unpaid come
 * from Accounting. {@link TenancyFake} covers the four widgets whose read side does not exist: the
 * indexation projection, the two invented milestones in the lifecycle, the document list and the
 * landlord's side of the parties card.
 *
 * <p><b>Four queries for the page, not four per card.</b> The register row, the outstanding
 * balances, the arrears board and the deposit are each asked once. The contacts are a bounded
 * fan-out over the parties of ONE tenancy, which is the same trade {@code TenanciesScreenController}
 * documents at register scale.
 *
 * <p><b>Nothing about arrears is decided here.</b> The colour arrives from accounting and is handed
 * to {@code arrearsPill} verbatim, exactly as the register and the unit screen do — A7's whole
 * point is that the derived board never becomes an opinion something else recomputes.
 */
@Controller
public class TenancyScreenController {

    /** `dd.mm.rrrr`, this codebase's stated date convention — see {@code TimelineScreenController}'s
     *  note on why the format is stated rather than inherited from the server locale. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * How long before the end a fixed-term tenancy's notice window opens.
     *
     * <p>Three months is the prototype's figure and it is a PLACEHOLDER, not a legal rule: the
     * notice period belongs to the contract and this application does not store one. It is named
     * here rather than written into the lifecycle list so that the day a notice period is recorded,
     * one constant disappears instead of a date being hunted for inside a builder.
     */
    private static final int NOTICE_MONTHS = 3;

    /** One person on the contract. Same shape {@code UnitScreenController.Person} carries; kept
     *  separate rather than shared because the two screens' cards are free to diverge. */
    public record Person(String initials, String name, String email, String phone) {
    }

    /**
     * One row of the deposit card.
     *
     * @param agreed  what the contract says, from {@code acc_deposit} — never twice the rent. The
     *                prototype falls back to a multiple of the rent when it has no figure; this
     *                does not, because a computed deposit rendered beside a real one is a term
     *                nobody agreed (V22's argument about the component split, applied here).
     * @param held    agreed minus what is still unpaid. Money actually in hand.
     * @param shortfall zero when the deposit is full. Positive is what is missing.
     * @param pct     held as a share of agreed, for the bar. Capped at 100 so an overpayment cannot
     *                draw a segment wider than its track.
     */
    public record Deposit(BigDecimal agreed, BigDecimal held, BigDecimal shortfall, int pct,
                          boolean full, boolean settled) {
    }

    /** One segment of the {@code progressBar} fragment, which reads {@code pct} and {@code tone}. */
    public record Segment(int pct, String tone) {
    }

    /** One milestone on the contract's lifecycle rail. */
    public record Milestone(String title, String subline, String date, String tone,
                            boolean passed) {
    }

    /**
     * The whole contract as the template reads it.
     *
     * @param reference DERIVED, not stored — see {@link #reference}. Rendered as a reference a
     *                  person can quote, never as a number this application issued.
     * @param balance   NEGATIVE when the tenant owes, the sign a ledger carries and the sign the
     *                  register already renders. Accounting reports outstanding as positive, so it
     *                  is negated once, here.
     * @param colour    accounting's, verbatim. Null when the board has no row for this tenancy — it
     *                  has not spoken, which is not the same as green.
     */
    public record Contract(UUID tenancyId, UUID unitId, String reference, String where,
                           String tenantNames, String initials, String legalForm, boolean occasional,
                           String state, String stateTone, String term, String startDate,
                           String endDate, boolean openEnded, String monthsLeft, String noticeDate,
                           boolean split, BigDecimal rent, BigDecimal adminFee,
                           BigDecimal mediaAdvance, BigDecimal monthlyTotal, String rentDay,
                           BigDecimal balance, ArrearsColour colour, String paymentReference,
                           List<Person> tenants, List<Person> guarantors) {
    }

    private final TenancyBoardProjection tenancies;
    private final InvoiceRepository invoices;
    private final ArrearsBoardProjection arrears;
    private final DepositRepository deposits;
    private final ContactDirectory directory;
    private final Clock clock;

    public TenancyScreenController(TenancyBoardProjection tenancies, InvoiceRepository invoices,
                                   ArrearsBoardProjection arrears, DepositRepository deposits,
                                   ContactDirectory directory, Clock clock) {
        this.tenancies = tenancies;
        this.invoices = invoices;
        this.arrears = arrears;
        this.deposits = deposits;
        this.directory = directory;
        this.clock = clock;
    }

    @GetMapping("/tenancies/{tenancyId}")
    public String tenancy(@PathVariable UUID tenancyId, WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        // Unknown and foreign are the same 404, as everywhere else here: an id that answers
        // differently for a tenancy in another agency tells a caller it exists.
        var row = tenancies.forTenancy(workspaceId, tenancyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        var contract = contract(workspaceId, row);
        var deposit = deposit(workspaceId, tenancyId);
        model.addAttribute("contract", contract);
        model.addAttribute("deposit", deposit);
        // One filled segment on a neutral track — the progressBar fragment's own contract, which is
        // a list of {pct, tone} summing to at most 100. Built here rather than in the template so
        // the tone is decided once, beside the figure that decides it.
        model.addAttribute("depositSegments", deposit == null ? List.of()
            // `green`, not `paid` — the progress bar has its own three tones and `paid` is a PILL
            // tone. A tone that does not exist renders as an unstyled segment with no error to say
            // so, which is the defect TemplateHygieneTest's fourth tripwire exists for.
            : List.of(new Segment(deposit.pct(), deposit.full() ? "green" : "warn")));
        model.addAttribute("landlord", workspace.name());
        model.addAttribute("lifecycle", lifecycle(row, contract));
        model.addAttribute("indexation", TenancyFake.indexation(row.monthlyTotal(), row.rent()));
        model.addAttribute("documents", TenancyFake.documents(row.legalForm()));
        return "tenancy";
    }

    private Contract contract(UUID workspaceId, TenancyDetailRow row) {
        var owed = invoices.outstandingByTenancy(workspaceId)
            .getOrDefault(row.tenancyId(), BigDecimal.ZERO);
        var colour = arrears.forWorkspace(workspaceId).stream()
            .filter(entry -> entry.tenancyId().equals(row.tenancyId()))
            .map(ArrearsBoardProjection.Row::colour)
            .findFirst().orElse(null);
        var tenants = people(workspaceId, row.tenantContactIds());
        boolean occasional = row.legalForm() == LegalForm.OKAZJONALNY;

        return new Contract(row.tenancyId(), row.unitId(), reference(row),
            row.propertyAddress() + " · " + row.unitName(),
            String.join(", ", tenants.stream().map(Person::name).toList()),
            tenants.isEmpty() ? "" : tenants.getFirst().initials(),
            legalForm(row.legalForm()), occasional,
            stateName(row.state()), stateTone(row.state()),
            term(row), DATE.format(row.startDate()),
            row.endDate() == null ? null : DATE.format(row.endDate()), row.endDate() == null,
            monthsLeft(row.endDate()), noticeDate(row.endDate()),
            row.componentSplit(), row.rent(), row.adminFee(), row.mediaAdvance(),
            row.monthlyTotal(), row.rentDay() + ". dnia miesiąca", owed.negate(), colour,
            row.paymentReference(), tenants, people(workspaceId, row.guarantorContactIds()));
    }

    /**
     * {@code NJ-2026/HOZA42-2A} — a reference a person can quote on the phone.
     *
     * <p><b>Derived from the contract's own facts and stored nowhere.</b> This application issues no
     * contract numbers; the prototype draws one because an agency has one, and until there is a
     * command that assigns one, a rendered string is the honest half of that. It is stable — the
     * start year, the property address and the unit name do not change — so quoting it twice gives
     * the same answer, which is the one property that makes a reference usable at all.
     *
     * <p>It is emphatically NOT unique, and for the same reason {@link PaymentReferences} says so of
     * its own output: two properties whose addresses reduce to the same letters produce the same
     * reference. Nothing matches on it, so a collision costs a conversation rather than a payment
     * landing on the wrong tenancy.
     *
     * <p>ASCII, via the same reduction PaymentReferences uses — {@code Ł} is not in {@code A-Z} and
     * a reference gets typed into forms that mangle diacritics.
     */
    static String reference(TenancyDetailRow row) {
        return "NJ-" + row.startDate().getYear() + "/"
            + segment(street(row.propertyAddress()), "ADRES") + "-"
            + segment(row.unitName(), "LOKAL");
    }

    /**
     * The street and number, which is the part of an address a person says.
     *
     * <p>Everything from the first comma on is a postcode and a city, and running them into the
     * segment is what turned {@code ul. Hoża 42, 00-516 Warszawa} into {@code ULHOA420} — eight
     * characters of which three were half a postcode. The leading street type goes for the same
     * reason: {@code UL} is two of the eight characters and distinguishes nothing, since every
     * address in the register has one.
     */
    private static String street(String address) {
        if (address == null) {
            return "";
        }
        String head = address.split(",")[0];
        return head.replaceFirst("(?i)^\\s*(ul|al|pl|os|ulica|aleja|plac|osiedle)\\.?\\s+", "");
    }

    /**
     * At most ten characters of {@code A-Z0-9}, with Polish letters TRANSLITERATED rather than
     * dropped.
     *
     * <p>{@link PaymentReferences} reduces by deletion — {@code Ż} is not in {@code A-Z}, so it
     * vanishes — and that is right there and wrong here. A transfer title is matched by machine
     * against what a tenant typed into a bank form, so its reduction is pinned to stored data and
     * changing it would be a migration ({@code PaymentReferencesTest} exists to say so). This
     * reference is read aloud and written down by people, and {@code HOA42} for Hoża is a reference
     * nobody recognises. So: same ASCII target, different route to it.
     *
     * <p>Empty falls back to a word rather than producing a reference with a hole in it, which
     * would read as a rendering fault rather than as an address nobody entered.
     */
    private static String segment(String raw, String fallback) {
        String reduced = raw == null ? "" : ascii(raw).toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]", "");
        if (reduced.isEmpty()) {
            return fallback;
        }
        return reduced.length() > 10 ? reduced.substring(0, 10) : reduced;
    }

    /**
     * Polish letters to their base forms.
     *
     * <p>{@code Ł} needs its own pair: it is the one Polish letter that is not a base letter plus a
     * combining mark, so NFD decomposition leaves it alone and a {@code \p{M}} strip cannot reach
     * it. Written out rather than pulled from a library so the one exception is visible.
     */
    private static String ascii(String raw) {
        String decomposed = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return decomposed.replace('Ł', 'L').replace('ł', 'l');
    }

    /**
     * The deposit as accounting holds it, or null when none was ever charged.
     *
     * <p>Null rather than a zeroed record: a tenancy signed without a deposit and a deposit of zero
     * złoty are different, and a bar drawn at 0% for the first would claim money is missing. The
     * template renders the absence instead.
     */
    private Deposit deposit(UUID workspaceId, UUID tenancyId) {
        var held = deposits.find(workspaceId, tenancyId).orElse(null);
        if (held == null) {
            return null;
        }
        BigDecimal agreed = held.nominalAmount();
        // unpaid is what the tenant still owes on it, so in-hand is the difference. Clamped at zero
        // because an overpayment must not render as a deposit larger than the one agreed.
        BigDecimal inHand = agreed.subtract(held.unpaid().max(BigDecimal.ZERO));
        BigDecimal shortfall = held.unpaid().max(BigDecimal.ZERO);
        int pct = agreed.signum() <= 0 ? 100 : Math.min(100, inHand.max(BigDecimal.ZERO)
            .multiply(BigDecimal.valueOf(100))
            .divide(agreed, 0, RoundingMode.DOWN).intValue());
        return new Deposit(agreed, inHand, shortfall, pct, held.isPaid(), held.settled());
    }

    /**
     * The contract's life, in the order it happens.
     *
     * <p>Two of these are real dates from the register — signing and the end — and two are the
     * prototype's, marked by {@link TenancyFake}. They are one list rather than two cards because
     * that is what a lifecycle is; the template flags the invented ones individually instead, which
     * is the only arrangement that keeps a real end date from being read as invented along with the
     * indexation notice sitting above it.
     *
     * <p>An open-ended tenancy gets no notice window and no end: there is no date to count back
     * from, and inventing one would put a deadline on a contract that does not have one.
     */
    private List<Milestone> lifecycle(TenancyDetailRow row, Contract contract) {
        LocalDate today = LocalDate.now(clock);
        var milestones = new ArrayList<Milestone>();
        milestones.add(new Milestone("Umowa podpisana",
            contract.occasional()
                ? "Najem okazjonalny — akt notarialny o poddaniu się egzekucji"
                : "Forma pisemna",
            DATE.format(row.startDate()), "neutral", !row.startDate().isAfter(today)));
        milestones.addAll(TenancyFake.milestones(contract.occasional()));
        if (row.endDate() != null) {
            LocalDate notice = row.endDate().minusMonths(NOTICE_MONTHS);
            milestones.add(new Milestone("Okno wypowiedzenia",
                "Ostatni moment na wypowiedzenie bez odnowienia — okres wypowiedzenia nie jest "
                    + "jeszcze zapisywany w umowie, więc to " + NOTICE_MONTHS + " miesiące przyjęte "
                    + "z prototypu",
                DATE.format(notice), "warn", !notice.isAfter(today)));
            milestones.add(new Milestone("Koniec umowy", "Odnowienie albo zdanie lokalu",
                DATE.format(row.endDate()), "green", !row.endDate().isAfter(today)));
        }
        return milestones;
    }

    /**
     * {@code 01.09.2025 – ∞} for an indefinite tenancy — the register's own rendering, and the same
     * argument: a null end date means the unit does not free up at all, so an em dash would read as
     * "unknown" where the infinity sign says what is true.
     */
    private static String term(TenancyDetailRow row) {
        return DATE.format(row.startDate()) + " – "
            + (row.endDate() == null ? "∞" : DATE.format(row.endDate()));
    }

    /**
     * How long is left, in correctly-declined Polish.
     *
     * <p>"bezterminowa" for an open-ended tenancy and "po terminie" for one already past its end —
     * both real states, and both wrong as "0 miesięcy", which reads as "ends this month". Counts
     * whole months, so a tenancy ending in eleven days reports {@code 0 miesięcy} and the caption
     * beside it is what says a decision is due.
     */
    private String monthsLeft(LocalDate endDate) {
        if (endDate == null) {
            return "bezterminowa";
        }
        LocalDate today = LocalDate.now(clock);
        if (endDate.isBefore(today)) {
            return "po terminie";
        }
        long months = ChronoUnit.MONTHS.between(today, endDate);
        return PolishPlural.count(months, "miesiąc", "miesiące", "miesięcy");
    }

    private static String noticeDate(LocalDate endDate) {
        return endDate == null ? null : DATE.format(endDate.minusMonths(NOTICE_MONTHS));
    }

    /**
     * Contact ids as named people, in the order the projection gave them.
     *
     * <p>A contact the directory does not know is dropped rather than rendered as a blank card: the
     * read is workspace-scoped and answers empty for unknown and foreign alike, and a placeholder
     * person would be this screen inventing one. Same rule {@code UnitScreenController.people}
     * follows, and it is a rule about parties rather than about either screen.
     */
    private List<Person> people(UUID workspaceId, List<UUID> contactIds) {
        return contactIds.stream()
            .map(id -> directory.find(workspaceId, id).orElse(null))
            .filter(java.util.Objects::nonNull)
            .map(contact -> new Person(
                letter(contact.givenName()) + letter(contact.surname()),
                (contact.givenName() + " " + contact.surname()).trim(),
                contact.email(), contact.phone()))
            .toList();
    }

    private static String letter(String name) {
        return name == null || name.isBlank() ? "" : name.substring(0, 1).toUpperCase();
    }

    /**
     * The legal form as a manager says it.
     *
     * <p>A switch rather than a lookup keyed on the enum, so adding a fourth form is a compile error
     * here instead of a screen rendering the constant's own SHOUTING name to a landlord — the same
     * call {@code UnitScreenController} makes, and made the same way so the two cannot diverge on
     * the words.
     */
    private static String legalForm(LegalForm form) {
        return switch (form) {
            case ZWYKLY -> "Najem zwykły";
            case OKAZJONALNY -> "Najem okazjonalny";
            case INSTYTUCJONALNY -> "Najem instytucjonalny";
        };
    }

    /**
     * The tenancy's own state, which is PM's answer and not accounting's.
     *
     * <p>Deliberately separate from the arrears pill beside it. This says whether the contract is
     * running; that says whether the tenant has paid. The prototype merges them into one word and
     * the merged word is wrong for a reserved tenancy that owes nothing and for an active one that
     * owes everything — the same argument unit.html's two-pill note already makes about market
     * state.
     */
    private static String stateName(Tenancy.State state) {
        return switch (state) {
            case RESERVED -> "Zarezerwowana";
            case ACTIVE -> "Aktywna";
            case ENDED -> "Zakończona";
            case CANCELLED -> "Anulowana";
        };
    }

    private static String stateTone(Tenancy.State state) {
        return switch (state) {
            case RESERVED -> "warn";
            case ACTIVE -> "paid";
            case ENDED, CANCELLED -> "neutral";
        };
    }
}
