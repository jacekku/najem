package pl.najem.app.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.ContactService;
import pl.najem.contacts.application.InterestNotActiveException;
import pl.najem.contacts.application.InterestService;
import pl.najem.contacts.application.InterestedParty;
import pl.najem.contacts.application.NoSuchContactException;
import pl.najem.contacts.application.NoSuchInterestException;
import pl.najem.contacts.application.UnitInterestQuery;
import pl.najem.pm.application.TenancyService;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.OverlappingTenancyException;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.TenancyPeriod;
import pl.najem.pm.domain.Term;
import pl.najem.reporting.application.UnitBoardQuery;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The reservation screen's first phase: who else is on the agreement besides the lead who said yes.
 *
 * <p>The lead comes from {@code interestId}, not from a drafted list — that is what distinguishes
 * them from every co-tenant and guarantor added here, and why they cannot be removed on this screen.
 * Everybody else is held in {@link PartiesDraft}, carried through the query string so the screen
 * stays bookmarkable and needs no session.
 */
@Controller
public class ReserveScreenController {

    /**
     * `dd.mm.rrrr`, this codebase's stated date convention — same pattern, same reasoning as
     * {@link TimelineScreenController#DATE} and {@link PaymentReferences}: no
     * {@code thymeleaf-extras-java8time} is on this module's classpath, so {@code #temporals} does
     * not exist, and a raw {@link LocalDate} concatenated into a template's {@code th:text} renders
     * its own {@code toString()} — ISO, not this app's convention. Formatted here, in Java, rather
     * than left to the template.
     */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * A booked period, dates pre-formatted for the template — {@code end} stays {@code null} for an
     * indefinite tenancy rather than becoming the string {@code "null"}, so the template's own
     * {@code period.end() == null} check still means what it says.
     */
    public record BookedPeriodView(UUID tenancyId, String start, String end) {
        static BookedPeriodView of(TenancyPeriod period) {
            return new BookedPeriodView(period.tenancyId(), DATE.format(period.start()),
                period.end() == null ? null : DATE.format(period.end()));
        }
    }

    private final UnitBoardQuery units;
    private final UnitInterestQuery interested;
    private final ContactDirectory directory;
    private final ContactService contacts;
    private final InterestService interests;
    private final TenancyService tenancies;
    private final Clock clock;

    public ReserveScreenController(UnitBoardQuery units, UnitInterestQuery interested,
                                   ContactDirectory directory, ContactService contacts,
                                   InterestService interests, TenancyService tenancies, Clock clock) {
        this.units = units;
        this.interested = interested;
        this.directory = directory;
        this.contacts = contacts;
        this.interests = interests;
        this.tenancies = tenancies;
        this.clock = clock;
    }

    @GetMapping("/units/{unitId}/reserve")
    public String reserve(@PathVariable UUID unitId,
                          @RequestParam UUID interestId,
                          @RequestParam(name = "tenant", required = false) List<String> tenants,
                          @RequestParam(name = "guarantor", required = false) List<String> guarantors,
                          @RequestParam(name = "role", required = false) String role,
                          @RequestParam(name = "q", required = false) String term,
                          @RequestParam(name = "parties", required = false) String parties,
                          WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        var unit = units.forUnit(workspaceId, unitId, LocalDate.now(clock))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var lead = activeLead(workspaceId, unitId, interestId);
        var draft = PartiesDraft.of(tenants, guarantors);

        model.addAttribute("unit", unit);
        model.addAttribute("lead", lead);
        model.addAttribute("interestId", interestId);
        model.addAttribute("tenants", named(workspaceId, draft.tenants()));
        model.addAttribute("guarantors", named(workspaceId, draft.guarantors()));
        // Raw ids alongside the named views: rebuilding a "remove one" or "add one" link from
        // NamedParty in the template would mean invoking contactId() through a SpringEL projection,
        // which this codebase otherwise avoids in favour of explicit accessor calls.
        model.addAttribute("tenantIds", draft.tenants());
        model.addAttribute("guarantorIds", draft.guarantors());

        if ("done".equals(parties)) {
            return terms(workspaceId, unit, lead, draft, model);
        }

        model.addAttribute("role", role == null ? "tenant" : role);
        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        model.addAttribute("hits", directory.search(workspaceId, term));
        return "reserve-parties";
    }

    /**
     * {@code activeForUnit} is the gate: an interest that is withdrawn, converted, foreign or
     * unknown is simply not in the list and the screen 404s, which is the same undifferentiated
     * answer every other id gets here. Shared by the GET and the POST so both see the same lead.
     */
    private InterestedParty activeLead(UUID workspaceId, UUID unitId, UUID interestId) {
        return interested.activeForUnit(workspaceId, unitId).stream()
            .filter(p -> p.interestId().equals(interestId))
            .findFirst()
            .orElseThrow(() -> new NoSuchInterestException(interestId));
    }

    /**
     * The terms phase's defaults come from what somebody said before what the unit asks for — the
     * lead's own figures, then the unit's — because a prefilled number the manager did not say is
     * the thing they are least likely to check.
     */
    private String terms(UUID workspaceId, UnitBoardQuery.Row unit, InterestedParty lead,
                         PartiesDraft draft, Model model) {
        LocalDate startDate = lead.desiredStart() != null ? lead.desiredStart() : LocalDate.now(clock);
        BigDecimal monthlyTotal = lead.willingToPay() != null ? lead.willingToPay() : unit.baseRent();
        String paymentReference = PaymentReferences.suggest(unit.name(), startDate);
        return termsAgain(workspaceId, unit, lead, draft, null, startDate, null, "indefinite",
            monthlyTotal, false, null, null, null, 10, null, paymentReference, model);
    }

    /**
     * The one method that builds the terms model, for both the first render and a re-render after
     * a rejected submission — so the two cannot drift apart.
     */
    private String termsAgain(UUID workspaceId, UnitBoardQuery.Row unit, InterestedParty lead,
                              PartiesDraft draft, String legalForm, LocalDate startDate,
                              LocalDate endDate, String termKind, BigDecimal monthlyTotal,
                              boolean componentSplit, BigDecimal rent, BigDecimal adminFee,
                              BigDecimal mediaAdvance, int rentDay, BigDecimal depositAmount,
                              String paymentReference, Model model) {
        model.addAttribute("unit", unit);
        model.addAttribute("lead", lead);
        model.addAttribute("interestId", lead.interestId());
        model.addAttribute("tenants", named(workspaceId, draft.tenants()));
        model.addAttribute("guarantors", named(workspaceId, draft.guarantors()));
        model.addAttribute("tenantIds", draft.tenants());
        model.addAttribute("guarantorIds", draft.guarantors());
        model.addAttribute("legalForms", LegalForm.values());
        // The calendar the manager is typing against, read before they type rather than explained
        // after they collide. From the unit's stream, which is the list reserve() checks against —
        // see TenancyService.periodsOf for why not the reporting projection.
        model.addAttribute("booked", tenancies.periodsOf(workspaceId, unit.unitId()).stream()
            .map(BookedPeriodView::of).toList());
        model.addAttribute("legalForm", legalForm);
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("termKind", termKind);
        model.addAttribute("monthlyTotal", monthlyTotal);
        model.addAttribute("componentSplit", componentSplit);
        model.addAttribute("rent", rent);
        model.addAttribute("adminFee", adminFee);
        model.addAttribute("mediaAdvance", mediaAdvance);
        model.addAttribute("rentDay", rentDay);
        model.addAttribute("depositAmount", depositAmount);
        model.addAttribute("paymentReference", paymentReference);
        return "reserve-terms";
    }

    /**
     * A guarantor the agency does not already hold — which is the ordinary case, since a guarantor
     * has no reason to be in the system before the agreement they are guaranteeing.
     *
     * <p>Registered under {@code contract}, not {@code legitimate-interest}: see
     * {@link ContactService#registerParty}. A person created here and then abandoned mid-form is a
     * real orphan — visible on a search and erasable, the same trade the interest form already takes.
     */
    @PostMapping("/units/{unitId}/reserve/parties")
    public String addParty(@PathVariable UUID unitId,
                           @RequestParam UUID interestId,
                           @RequestParam(name = "tenant", required = false) List<String> tenants,
                           @RequestParam(name = "guarantor", required = false) List<String> guarantors,
                           @RequestParam String role,
                           @RequestParam(required = false) String contactId,
                           @RequestParam(required = false) String givenName,
                           @RequestParam(required = false) String surname,
                           @RequestParam(required = false) String email,
                           @RequestParam(required = false) String phone,
                           @RequestParam(defaultValue = "false") boolean infoClauseServed,
                           WebWorkspace workspace) {
        UUID workspaceId = workspace.workspaceId();
        UUID added = UnitScreenController.chosen(contactId)
            .orElseGet(() -> contacts.registerParty(workspaceId,
                new ContactDetails(givenName, surname, email, phone),
                infoClauseServed, LocalDate.now(clock)));

        var draft = PartiesDraft.of(tenants, guarantors);
        List<String> nextTenants = ids(draft.tenants());
        List<String> nextGuarantors = ids(draft.guarantors());
        ("guarantor".equals(role) ? nextGuarantors : nextTenants).add(added.toString());
        // Re-validated rather than appended blindly: the duplicate rule has to hold for the person
        // just added, and PartiesDraft is the only place that rule lives. The result is what builds
        // the redirect below, so this is not a discarded call run only for its exception.
        var validated = PartiesDraft.of(nextTenants, nextGuarantors);

        return "redirect:" + UriComponentsBuilder.fromPath("/units/{unitId}/reserve")
            .queryParam("interestId", interestId)
            .queryParam("tenant", ids(validated.tenants()))
            .queryParam("guarantor", ids(validated.guarantors()))
            .queryParam("role", role)
            .buildAndExpand(unitId).toUriString();
    }

    /**
     * The agreement is signed.
     *
     * <p>The reservation goes first because it is the fact, and it is the only step that can fail
     * hard. A failure after it leaves a real tenancy beside a stale lead — visible on the unit screen
     * and fixable. Converting first would mark a lead as won for a tenancy that does not exist, and
     * nothing would show that.
     */
    @PostMapping("/units/{unitId}/reserve")
    public String create(@PathVariable UUID unitId,
                         @RequestParam UUID interestId,
                         @RequestParam(name = "tenant", required = false) List<String> tenants,
                         @RequestParam(name = "guarantor", required = false) List<String> guarantors,
                         @RequestParam String legalForm,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                         @RequestParam(defaultValue = "indefinite") String termKind,
                         @RequestParam BigDecimal monthlyTotal,
                         @RequestParam(defaultValue = "false") boolean componentSplit,
                         @RequestParam(required = false) BigDecimal rent,
                         @RequestParam(required = false) BigDecimal adminFee,
                         @RequestParam(required = false) BigDecimal mediaAdvance,
                         @RequestParam int rentDay,
                         @RequestParam(required = false) BigDecimal depositAmount,
                         @RequestParam String paymentReference,
                         WebWorkspace workspace, Model model,
                         RedirectAttributes flash) {
        UUID workspaceId = workspace.workspaceId();
        var unit = units.forUnit(workspaceId, unitId, LocalDate.now(clock))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var lead = activeLead(workspaceId, unitId, interestId);
        var draft = PartiesDraft.of(tenants, guarantors);
        // The lead is prepended below, not drafted, so PartiesDraft's own duplicate rule never
        // sees them. Refused here rather than left to Tenancy, which has no rule against it and
        // would carry the duplicate into TenancyReserved as two tenants who are one person.
        if (draft.tenants().contains(lead.contactId())) {
            throw new DuplicatePartyException(lead.contactId());
        }
        // The ownership gate every other read of a drafted id already goes through on the GET
        // (see named()) and on a rejected re-render (termsAgain -> named()) — but not, until now,
        // on the path that actually commits. A drafted id can go stale between the GET that last
        // validated it and this POST: another manager may have erased the contact in between. Run
        // before ReserveTenancy is built, and before anything commits, so a foreign or erased id
        // 404s here exactly as it would have on the GET, rather than three commits later inside
        // becameContractParty.
        draft.tenants().forEach(id -> directory.requireIn(workspaceId, id));
        draft.guarantors().forEach(id -> directory.requireIn(workspaceId, id));

        List<UUID> allTenants = new ArrayList<>();
        allTenants.add(lead.contactId());
        allTenants.addAll(draft.tenants());

        // "fixed" wins only paired with an actual endDate: a stray "fixed" with no date, or any
        // other value, reserves indefinitely rather than guessing. The radio is read regardless of
        // whether the page's script ran, so a manager who changes their mind and picks "na czas
        // nieokreślony" without clearing the date field is taken at their word.
        Term term = "fixed".equals(termKind) && endDate != null
            ? new Term.FixedTerm(endDate) : new Term.Indefinite();

        var command = new ReserveTenancy(null, workspaceId, unitId, allTenants, draft.guarantors(),
            startDate, term, LegalForm.valueOf(legalForm.toUpperCase()),
            new MonthlyAmount(monthlyTotal, componentSplit
                ? new MonthlyAmount.Breakdown(zeroIfNull(rent), zeroIfNull(adminFee), zeroIfNull(mediaAdvance))
                : null),
            rentDay, depositAmount, paymentReference);

        TenancyService.Reservation reservation;
        try {
            reservation = tenancies.reserve(workspaceId, command);
        } catch (OverlappingTenancyException e) {
            // The manager's input caused this, and an error page would be correct and would also
            // throw away the form. Re-render with everything they typed still in its field. The
            // exception's own message is English and names the colliding tenancy by UUID — neither
            // is something to show a manager, so this is our own Polish copy.
            model.addAttribute("error", overlapMessage(e.blocking()));
            return termsAgain(workspaceId, unit, lead, draft, legalForm, startDate, endDate, termKind,
                monthlyTotal, componentSplit, rent, adminFee, mediaAdvance, rentDay,
                depositAmount, paymentReference, model);
        }

        LocalDate today = LocalDate.now(clock);
        interests.convert(workspaceId, interestId, reservation.tenancyId(), today);
        allTenants.forEach(id -> contacts.becameContractParty(workspaceId, id, today));
        draft.guarantors().forEach(id -> contacts.becameContractParty(workspaceId, id, today));

        flash.addFlashAttribute("warnings", reservation.warnings());
        return "redirect:/tenancies/" + reservation.tenancyId() + "/timeline";
    }

    /**
     * A blank component under a TICKED split is 0, not "unspecified" — the manager said the
     * contract splits the total and left one line at nothing, which is a real zero-valued
     * component. This is deliberately not reached when the checkbox is unticked: there,
     * {@link MonthlyAmount#componentSplitInContract()} must read false, which needs a null
     * breakdown, not one built from three zeros — "no split" and "a split with a zero adminFee"
     * are legally different, and only the caller (which knows whether the box was ticked) can
     * tell them apart.
     */
    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Polish, and about the period that <em>blocks</em> — never the domain exception's own English
     * message, which names the colliding tenancy by UUID.
     *
     * <p>It used to describe the dates the manager had just typed: "Lokal jest już zarezerwowany w
     * tym okresie: od 2027-01-01". That was not true — the unit is not reserved for the period they
     * asked for, it is reserved for somebody else's, and theirs is merely refused. Saying which one
     * blocks is also the only way to say when it frees.
     *
     * <p>The free-up date is the blocking period's own end, not the day after it. Periods are
     * half-open, so a tenancy ending 30 Jun leaves 30 Jun available — telling a manager to come back
     * on the 1st would cost the agency a day of rent on every back-to-back letting.
     *
     * <p>A null end is an indefinite tenancy. It is not a missing date and must not render as one:
     * the unit does not free up at all until that tenancy is ended, and that is the answer.
     */
    private static String overlapMessage(TenancyPeriod blocking) {
        if (blocking.end() == null) {
            return "Lokal jest już zajęty bezterminowo od " + DATE.format(blocking.start())
                + " — nie zwolni się, dopóki tamten najem nie zostanie zakończony.";
        }
        return "Lokal jest już zarezerwowany od " + DATE.format(blocking.start()) + " do "
            + DATE.format(blocking.end())
            + ". Zwolni się " + DATE.format(blocking.end()) + " — nowy najem może zacząć się tego dnia.";
    }

    private List<NamedParty> named(UUID workspaceId, List<UUID> ids) {
        return ids.stream()
            .map(id -> new NamedParty(id, directory.find(workspaceId, id)
                    .orElseThrow(() -> new NoSuchContactException(id)),
                ids.stream().filter(other -> !other.equals(id)).toList()))
            .toList();
    }

    private static List<String> ids(List<UUID> parties) {
        return parties.stream().map(UUID::toString).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * A drafted party with a name to show. The id alone renders as a UUID, which tells nobody
     * anything.
     *
     * <p>{@code others} is this party's role with this party taken out — what the Usuń link has to
     * carry. It is computed here rather than in the template because the template's way of computing
     * it did not work: a SpringEL selection {@code ${ids.?[#this != party.contactId()]}} rebinds the
     * root object to each element, so {@code party} was resolved against a {@code UUID} and every
     * render of this screen with any drafted party threw. The screen was only ever rendered empty by
     * a test, so nothing caught it until somebody added a guarantor by hand.
     */
    public record NamedParty(UUID contactId, ContactDetails details, List<UUID> others) {
    }
}
