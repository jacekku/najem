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
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.ContactService;
import pl.najem.contacts.application.InterestedParty;
import pl.najem.contacts.application.InterestService;
import pl.najem.contacts.application.NoSuchContactException;
import pl.najem.contacts.application.UnitInterestQuery;
import pl.najem.reporting.application.UnitBoardQuery;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * One unit, and the people who have asked about it.
 *
 * <p>The unit's facts come from Reporting and the people from Contacts, and the two cannot be
 * merged into one read: Reporting has never seen a name, because the PII lookaside keeps names out
 * of the events it projects from. That is not an inconvenience to route around — it is what makes
 * erasure complete, and a projection holding a name would outlive the deletion.
 *
 * <p>Renders what it is given. Whether a unit is let, and which of the three market states it is
 * in, are the projection's answers.
 */
@Controller
public class UnitScreenController {

    /**
     * `dd.mm.rrrr`, this codebase's stated date convention — same pattern as
     * {@link TimelineScreenController#DATE} and {@link ReserveScreenController#DATE}: no
     * {@code thymeleaf-extras-java8time} on this module's classpath, so a raw {@link LocalDate}
     * concatenated into a template's {@code th:text} would render its own ISO {@code toString()}
     * instead.
     */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** {@link InterestedParty}, with {@code desiredStart} pre-formatted for the template —
     *  {@code null} stays {@code null} (nobody has said when), never the string {@code "null"}. */
    public record InterestedPartyView(UUID interestId, String fullName, String email, String phone,
                                      BigDecimal willingToPay, String desiredStart) {
        static InterestedPartyView of(InterestedParty party) {
            return new InterestedPartyView(party.interestId(), party.fullName(), party.email(),
                party.phone(), party.willingToPay(),
                party.desiredStart() == null ? null : DATE.format(party.desiredStart()));
        }
    }

    private final UnitBoardQuery units;
    private final UnitInterestQuery interested;
    private final Clock clock;
    private final ContactService contacts;
    private final InterestService interests;
    private final ContactDirectory directory;

    public UnitScreenController(UnitBoardQuery units, UnitInterestQuery interested, Clock clock,
                                ContactService contacts, InterestService interests, ContactDirectory directory) {
        this.units = units;
        this.interested = interested;
        this.clock = clock;
        this.contacts = contacts;
        this.interests = interests;
        this.directory = directory;
    }

    /**
     * Whether the manager picked somebody we already know.
     *
     * <p>Package-private and static so it can be tested without booting anything — the
     * {@code SearchGroupingTest} precedent. Blank is "no", and a malformed value is refused rather
     * than falling through to the create path: a garbled hidden field silently registering a second
     * person is the duplicate this screen exists to prevent. Refused as an
     * {@link InvalidContactIdException} — a 400, not a 500 — rather than the bare
     * {@link IllegalArgumentException} {@link UUID#fromString} throws, so the failure carries a type
     * {@link WebErrorAdvice} can map without also catching every unrelated bad-argument bug in scope.
     */
    static Optional<UUID> chosen(String contactId) {
        if (contactId == null || contactId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(contactId.strip()));
        } catch (IllegalArgumentException e) {
            throw new InvalidContactIdException(contactId, e);
        }
    }

    @GetMapping("/units/{unitId}")
    public String unit(@PathVariable UUID unitId,
                       @RequestParam(name = "q", required = false) String term,
                       @RequestParam(name = "contactId", required = false) String contactId,
                       WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        // Unknown and foreign are the same 404, as they are everywhere else here: an id that
        // answers differently for a unit in another agency tells a caller it exists.
        var unit = units.forUnit(workspaceId, unitId, LocalDate.now(clock))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("unit", unit);
        model.addAttribute("interested", interested.activeForUnit(workspaceId, unitId).stream()
            .map(InterestedPartyView::of).toList());

        // "Type something" and "nobody matched" are different answers and must not share a message,
        // for the same reason the search screen distinguishes them: a blank term returns no rows
        // exactly as a term that matched nothing does, so the difference cannot come from the result.
        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        model.addAttribute("hits", directory.search(workspaceId, term));

        // A well-formed contactId this workspace does not know is refused the same way a malformed
        // one is refused by chosen() itself: not silently downgraded to the create-a-new-person
        // path. It is a not-found, not bad input, so it is the same NoSuchContactException the POST
        // path already throws from ContactDirectory.requireIn — one concept, one type, one 404.
        var picked = chosen(contactId);
        model.addAttribute("chosenId", picked.orElse(null));
        model.addAttribute("chosen", picked
            .map(id -> directory.find(workspaceId, id).orElseThrow(() -> new NoSuchContactException(id)))
            .orElse(null));
        return "unit";
    }

    /**
     * Somebody phoned about this unit.
     *
     * <p>One endpoint and two paths, because from the manager's side it is one action. Which path
     * ran is the presence of {@code contactId} — the hidden field the "Wybierz" link fills in.
     *
     * <p>The person is registered before the interest, and both are separate transactions on the
     * services that own them. A failure between the two leaves a person with no interest, which a
     * manager can see and fix; the reverse would leave an interest pointing at nobody, which the
     * inner join would hide.
     */
    @PostMapping("/units/{unitId}/interests")
    public String addInterest(@PathVariable UUID unitId,
                              @RequestParam(required = false) String contactId,
                              @RequestParam(required = false) String givenName,
                              @RequestParam(required = false) String surname,
                              @RequestParam(required = false) String email,
                              @RequestParam(required = false) String phone,
                              // An unticked checkbox submits NOTHING, so this parameter is absent
                              // rather than "false" — and a primitive boolean with required=false
                              // and no default fails to bind a missing value. defaultValue is what
                              // makes "the manager did not tick it" the ordinary path.
                              @RequestParam(defaultValue = "false") boolean infoClauseServed,
                              @RequestParam(required = false) BigDecimal willingToPay,
                              @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desiredStart,
                              WebWorkspace workspace) {
        UUID workspaceId = workspace.workspaceId();
        UUID person = chosen(contactId).orElseGet(() -> contacts.registerLead(workspaceId,
            new ContactDetails(givenName, surname, email, phone),
            infoClauseServed, LocalDate.now(clock)));
        interests.register(workspaceId, person, unitId, willingToPay, desiredStart);
        return "redirect:/units/" + unitId;
    }

    /**
     * POST rather than DELETE because an HTML form cannot issue one, and the same shape the bank
     * screen's two buttons already use.
     *
     * <p>The service's own lookup is the workspace gate — it names the workspace, so a foreign or
     * unknown interest finds nothing and the command is refused before anything is appended. This
     * passes the workspace on rather than checking it here.
     */
    @PostMapping("/units/{unitId}/interests/{interestId}/withdraw")
    public String withdraw(@PathVariable UUID unitId, @PathVariable UUID interestId,
                           WebWorkspace workspace) {
        interests.withdraw(workspace.workspaceId(), interestId, LocalDate.now(clock));
        return "redirect:/units/" + unitId;
    }
}
