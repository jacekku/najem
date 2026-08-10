package pl.najem.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.ContactService;
import pl.najem.contacts.application.NoSuchContactException;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;

import java.net.URLEncoder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Creating a building, in two phases: who owns it, then in what shares.
 *
 * <p>Two phases rather than one screen because picking an owner is a server round trip, and a round
 * trip loses whatever is typed in a <em>sibling</em> form — the browser submits one form, not the
 * page. A single screen holding the address, the share inputs and the picker would silently reset
 * the shares every time the manager searched for a co-owner, and nothing would say so. Phase two
 * asks for shares once, after the owner list is settled, so there is nothing left to lose.
 *
 * <p><b>Phase one is one form, for the same reason.</b> It was three siblings carrying the address
 * as a hidden copy on two of them, and a hidden copy holds what the server last knew, not what the
 * manager has just typed — so typing an address and then searching threw it away. Every action here
 * is now a submit button on the single form that owns the one visible address input, distinguished
 * by {@code formaction} alone. With one field there is nothing to keep in sync (refactoring rule
 * 9), and the "Zapamiętaj adres" button that used to be the workaround is gone.
 *
 * <p><b>And every one of those buttons POSTs, then redirects.</b> The first version of the single
 * form kept the four non-creating buttons as {@code formmethod="get"}, which was a security defect:
 * a form whose own method is {@code post} carries Thymeleaf's hidden {@code _csrf} input, and a GET
 * submission serialises <em>every</em> field into the query string — so searching for an owner put
 * the session's CSRF token into the address bar, the history, and every proxy log between here and
 * the browser. With {@code HttpSessionCsrfTokenRepository} that token is good for the life of the
 * session, so a leaked URL is a usable forgery.
 *
 * <p>So phase one is Post/Redirect/Get: {@link #restate} takes the submission, validates the draft,
 * and redirects to {@link #form}, which renders and nothing else. The token travels in the POST
 * body and appears in no URL; the resulting GET stays the bookmarkable link {@code OwnerDraft}'s
 * javadoc promises; and because every POST ends in a redirect, reloading or going back never
 * prompts to resubmit. {@link #addOwner} already had this shape and is where it was copied from.
 *
 * <p>This is the shape {@link ReserveScreenController} already uses — draft, then terms — and that
 * is a reason, not a coincidence. A second write screen that behaves differently for no reason
 * costs the manager the thing they learned last week.
 */
@Controller
public class AddPropertyScreenController {

    private final PortfolioService portfolio;
    private final ContactDirectory directory;
    private final ContactService contacts;
    private final Clock clock;

    public AddPropertyScreenController(PortfolioService portfolio, ContactDirectory directory,
                                       ContactService contacts, Clock clock) {
        this.portfolio = portfolio;
        this.directory = directory;
        this.contacts = contacts;
        this.clock = clock;
    }

    /**
     * Renders, and only renders. Every state change arrives at {@link #restate} or
     * {@link #addOwner} and comes back here as a redirect, so this method is what a bookmark, a
     * reload and a back button all hit — none of which may re-do anything.
     */
    @GetMapping("/properties/new")
    public String form(@RequestParam(name = "address", required = false) String address,
                       @RequestParam(name = "owner", required = false) List<String> owners,
                       @RequestParam(name = "q", required = false) String term,
                       @RequestParam(name = "owners", required = false) String phase,
                       WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        var draft = OwnerDraft.of(owners);

        model.addAttribute("address", address == null ? "" : address);
        model.addAttribute("owners", named(workspaceId, draft.owners()));
        // Raw ids alongside the named views: rebuilding a "Wróć" link from NamedOwner in the
        // template would need a SpringEL projection over contactId(), and this codebase learned the
        // hard way that a SpEL selection rebinds the root to each element.
        model.addAttribute("ownerIds", draft.owners());

        if ("done".equals(phase)) {
            // Reached with nobody drafted means the manager typed the URL or went back after
            // removing the last owner. A property with no owner is a record of nothing and there is
            // no screen to add one afterwards, so this is a return to phase one, not a create.
            if (draft.owners().isEmpty()) {
                model.addAttribute("error", "Nieruchomość musi mieć co najmniej jednego właściciela.");
            } else if (address == null || address.isBlank()) {
                model.addAttribute("error", "Najpierw podaj adres nieruchomości.");
            } else {
                model.addAttribute("phase", "shares");
                model.addAttribute("term", null);
                model.addAttribute("asked", false);
                model.addAttribute("hits", List.of());
                return "property-new";
            }
        }

        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        model.addAttribute("hits", directory.search(workspaceId, term));
        model.addAttribute("phase", "owners");
        return "property-new";
    }

    /**
     * Every phase-one button that is not "Dodaj": Szukaj, picking a hit, Usuń and "Dalej — udziały".
     *
     * <p>One handler rather than four because the four submissions differ only in which field the
     * clicked button contributes, and nothing else. A picked hit arrives as another {@code owner} in
     * the very list Szukaj already submits, so "search" and "pick" are the same request; Usuń adds
     * {@code remove} and "Dalej" adds {@code owners=done}. Four bodies differing by one parameter
     * would be four places to forget the encoding below.
     *
     * <p>It does the work — parse, de-duplicate, drop the removed owner — and then redirects, so the
     * state ends up in the URL of a GET rather than in the body of a POST the browser would offer to
     * resubmit. The draft is validated here as well as in {@link #form} because a garbled or
     * duplicated id must be refused where it is submitted, not turned into a redirect to a URL that
     * then 400s.
     */
    // TODO: the hit list is not filtered against the owners already drafted, so a manager who
    // clicks somebody they have already added gets a 400 (OwnerDraft's duplicate rule) and, because
    // there is no generic error page, a blank browser screen mid-form. It fails safe rather than
    // silently, which is why this is not a bug — but the person is visible in both lists at once,
    // so the mistake is invited. Two candidate fixes, and they are not equivalent: drop drafted ids
    // from `hits` in form() so the button is not offered, or keep offering it and make it a no-op.
    // Prefer the first — a control that does nothing is its own puzzle. Note that ReserveScreen-
    // Controller's picker has exactly the same gap, so fix both or neither.
    @PostMapping("/properties/new")
    public String restate(@RequestParam(name = "address", required = false) String address,
                          @RequestParam(name = "owner", required = false) List<String> owners,
                          @RequestParam(name = "remove", required = false) String remove,
                          @RequestParam(name = "q", required = false) String term,
                          @RequestParam(name = "owners", required = false) String phase) {
        var draft = new OwnerDraft(without(OwnerDraft.of(owners).owners(), remove));
        return phaseOne(address, draft.owners(), term, phase);
    }

    /**
     * The building is entered.
     *
     * <p>The shares arrive as a parallel list positionally matched to the owners, which is what the
     * template's inputs produce — the n-th share belongs to the n-th owner. A length mismatch means
     * the form was tampered with or a param was dropped in transit, and assigning the shares anyway
     * would silently give somebody else's stake to the wrong person. Refused rather than trimmed.
     *
     * <p>Ownership of each drafted contact is checked before anything commits, for the reason
     * {@link ReserveScreenController#create} learned: a drafted id can go stale between the GET that
     * last validated it and this POST, and a foreign or erased id must 404 here rather than end up
     * on a created property naming nobody.
     */
    @PostMapping("/properties")
    public String create(@RequestParam String address,
                         @RequestParam(name = "owner", required = false) List<String> owners,
                         @RequestParam(name = "share", required = false) List<BigDecimal> shares,
                         WebWorkspace workspace, Model model, RedirectAttributes flash) {
        UUID workspaceId = workspace.workspaceId();
        var draft = OwnerDraft.of(owners);
        if (draft.owners().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A property needs at least one owner");
        }
        if (shares == null || shares.size() != draft.owners().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every owner needs a share");
        }
        draft.owners().forEach(id -> directory.requireIn(workspaceId, id));

        List<Owner> stakes = new ArrayList<>();
        for (int i = 0; i < draft.owners().size(); i++) {
            stakes.add(new Owner(draft.owners().get(i), shares.get(i)));
        }

        PortfolioService.CreatedProperty created;
        try {
            created = portfolio.createProperty(workspaceId, address, stakes);
        } catch (IllegalArgumentException blankAddress) {
            // Property.create's own refusal — a null/blank address. Caught here rather than
            // re-checked, so the rule stays in one place; converted here rather than in
            // WebErrorAdvice because a bare IllegalArgumentException is deliberately NOT mapped
            // there (see UnitScreenController.chosen) — catching it globally would also catch
            // every unrelated bad-argument bug in the package and turn it into a quiet 400.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, blankAddress.getMessage());
        }
        flash.addFlashAttribute("warnings", created.warnings());
        return "redirect:/properties/" + created.propertyId() + "/units";
    }

    /**
     * An owner the agency does not already hold. Registered under {@code contract}: the agency holds
     * this person's data because it manages their building under a contract with them, which is what
     * {@link ContactService#registerParty} stamps.
     */
    @PostMapping("/properties/new/owners")
    public String addOwner(@RequestParam(name = "address", required = false) String address,
                           @RequestParam(name = "owner", required = false) List<String> owners,
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

        List<String> next = ids(OwnerDraft.of(owners).owners());
        next.add(added.toString());
        // Re-validated rather than appended blindly: the duplicate rule has to hold for the person
        // just added, and OwnerDraft is the only place that rule lives. The result builds the
        // redirect below, so this is not a call kept only for its exception.
        var validated = OwnerDraft.of(next);

        // The search term is deliberately not carried: an owner has just been added, so the hit
        // list that produced them is spent. Szukaj keeps its own term, through restate.
        return phaseOne(address, validated.owners(), null, null);
    }

    /**
     * Where every phase-one POST lands: a plain GET of this screen, carrying the state in the query
     * string. One method because there is one encoding decision here and it was got wrong once
     * already — a second copy is a second chance to get it wrong (refactoring rule 9).
     *
     * <p>The free-text values are ENCODED. The pattern was copied from ReserveScreenController,
     * where every value is a UUID or a literal and {@code build()}'s lack of encoding costs nothing;
     * an address is text a manager types, and "Kwiatowa 1 &amp; 3, Sopot" came back as "Kwiatowa 1 "
     * because the browser read the ampersand as the start of the next parameter. Also #, + and %.
     *
     * <p>{@link URLEncoder} rather than the builder's own {@code encode()}: a query string is decoded
     * by the container as {@code application/x-www-form-urlencoded}, where a literal '+' means a
     * space — and {@code UriComponentsBuilder.encode()} leaves '+' alone, because it is legal in a
     * query per RFC 3986. Encoding with the rules the other end decodes with is the only pairing
     * that round-trips, and it is what AddUnitScreenController already does with a unit's name.
     * {@code build(true)} then says the parts are encoded already, so nothing encodes them twice.
     */
    private static String phaseOne(String address, List<UUID> owners, String term, String phase) {
        var uri = UriComponentsBuilder.fromPath("/properties/new")
            .queryParam("address", encoded(address));
        // Only when there is somebody. queryParam(name, emptyCollection) appends a BARE `owner` with
        // no `=`, which a servlet container hands back as a parameter whose value is the empty
        // string — and OwnerDraft refuses that, so searching with nobody drafted yet would redirect
        // to a URL that 400s.
        if (!owners.isEmpty()) {
            uri.queryParam("owner", ids(owners));
        }
        if (term != null && !term.isBlank()) {
            uri.queryParam("q", encoded(term));
        }
        if (phase != null && !phase.isBlank()) {
            uri.queryParam("owners", encoded(phase));
        }
        return "redirect:" + uri.build(true).toUriString();
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value == null ? "" : value, UTF_8);
    }

    /**
     * The Usuń button posts the id to drop rather than the list to keep. Sent as a plain string and
     * matched as one: a value that does not parse removes nobody, which the manager can see, whereas
     * refusing it would 400 a page for a stale button. Contrast {@code OwnerDraft}, which refuses a
     * garbled id because <em>dropping</em> one there would silently lose an owner.
     */
    private static List<UUID> without(List<UUID> drafted, String remove) {
        if (remove == null || remove.isBlank()) {
            return drafted;
        }
        return drafted.stream().filter(id -> !id.toString().equalsIgnoreCase(remove.strip())).toList();
    }

    private List<NamedOwner> named(UUID workspaceId, List<UUID> ids) {
        List<NamedOwner> named = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            UUID id = ids.get(i);
            named.add(new NamedOwner(id, directory.find(workspaceId, id)
                .orElseThrow(() -> new NoSuchContactException(id)), evenSplit(ids.size(), i)));
        }
        return named;
    }

    /**
     * The prefilled suggestion, and the last owner takes the remainder.
     *
     * <p>{@code 100 / n} to two places gives three owners 33.33 each, so accepting the defaults
     * created a property warned as "total 99.99" — a suggestion that is wrong by default, on the one
     * screen whose whole job is to make the total come out right. Handing the rounding loss to the
     * last share makes the default always total exactly 100.
     */
    private static String evenSplit(int count, int index) {
        var even = new BigDecimal("100").divide(BigDecimal.valueOf(count), 2, RoundingMode.DOWN);
        var share = index == count - 1
            ? new BigDecimal("100").subtract(even.multiply(BigDecimal.valueOf(count - 1L)))
            : even;
        return share.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static List<String> ids(List<UUID> owners) {
        return owners.stream().map(UUID::toString).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * A drafted owner with a name to show and the share to prefill. Both computed here, not in the
     * template: a SpringEL selection {@code ${ownerIds.?[#this != owner.contactId()]}} rebinds the
     * root object to each element, so {@code owner} resolves against a {@code UUID} and every render
     * with any drafted owner throws — and the even split is arithmetic that has to know the
     * position, which is exactly what a template expression cannot say without repeating itself.
     */
    public record NamedOwner(UUID contactId, ContactDetails details, String suggestedShare) {
    }
}
