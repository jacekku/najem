package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.um.application.WorkspaceAccess;

import java.util.Comparator;
import java.util.List;

/**
 * Ustawienia agencji: who this agency is, who works in it, and what it settles by.
 *
 * <p><b>The team is real, and it is the reason this screen was worth building.</b> Until now nothing
 * in the application listed who had access to an agency — the memberships existed, were enforced on
 * every request, and could not be seen. An access list nobody can read is an access list nobody
 * audits, which is the failure mode that matters here rather than a missing screen.
 *
 * <p><b>Two modules meet and neither learns about the other.</b> Usermanagement says who is a member
 * and what they hold; Contacts turns the linked contact id into a name. Composing the two is the
 * composition root's job, the same division {@code TenanciesScreenController} works under. A member
 * with no linked contact keeps their row and renders unnamed — dropping them to avoid a null would
 * under-report who has access, and that is the one direction this list must never be wrong in.
 *
 * <p><b>Everything below the team is {@link AccountFake}, and the template flags it.</b> The NIP,
 * the address, the settlement account, the billing defaults and the integrations are invented,
 * because this application stores none of them. They are drawn so the screen v2 designs can be
 * reviewed, and marked so a fake tax identity is never mistaken for one an invoice could use.
 *
 * <p>Readable by every member, not only an admin. Seeing who else is in the agency you already
 * belong to discloses nothing you cannot learn by working there, and gating it would make the one
 * list that shows who has access visible only to the people least likely to be surprised by it.
 * Editing is a different question and this screen does none — every field here is read-only, which
 * is why no {@code @PreAuthorize} appears.
 */
@Controller
public class AgencySettingsScreenController {

    /**
     * One member of the agency.
     *
     * @param name null when the account has no linked contact. Null rather than a placeholder for
     *             the same reason the profile does it: the template decides how to render an
     *             absence once, and "—" beside a real role is honest where an invented name is not.
     * @param you  the person reading the screen. Marked because an access list is read to find
     *             somebody, and the reader is the one row they can verify against.
     */
    public record Member(String initials, String name, String email, String role, boolean you) {
    }

    private final WorkspaceAccess access;
    private final ContactDirectory contacts;

    public AgencySettingsScreenController(WorkspaceAccess access, ContactDirectory contacts) {
        this.access = access;
        this.contacts = contacts;
    }

    @GetMapping("/ustawienia")
    public String settings(WebWorkspace workspace, Model model) {
        var team = team(workspace);
        model.addAttribute("agency", workspace.name());
        model.addAttribute("role", ProfileScreenController.roleName(workspace.role()));
        model.addAttribute("team", team);
        model.addAttribute("teamSummary",
            PolishPlural.count(team.size(), "osoba", "osoby", "osób"));
        model.addAttribute("identity", AccountFake.IDENTITY);
        model.addAttribute("billing", AccountFake.BILLING);
        model.addAttribute("integrations", AccountFake.INTEGRATIONS);
        return "settings";
    }

    /**
     * Every member, admins first and then by name.
     *
     * <p>Ordered here rather than left to the projection's map iteration order, which is a
     * {@code HashMap}'s and therefore arbitrary — a list that reshuffles between two loads of the
     * same page reads as if the membership changed. Admins first because the question this list is
     * usually opened to answer is who can add and remove people.
     *
     * <p>One contact lookup per member: a bounded fan-out over a team, not an N+1 over a portfolio.
     * {@code ContactDirectory} offers no bulk read, which is the same trade
     * {@code TenanciesScreenController} makes and documents.
     */
    private List<Member> team(WebWorkspace workspace) {
        return access.membersOf(workspace.workspaceId()).stream()
            .map(member -> member(workspace, member))
            .sorted(Comparator.comparing((Member m) -> !"Administrator".equals(m.role()))
                .thenComparing(m -> m.name() == null ? "￿" : m.name()))
            .toList();
    }

    private Member member(WebWorkspace workspace, WorkspaceAccess.Member member) {
        ContactDetails contact = member.contactId() == null ? null
            : contacts.find(workspace.workspaceId(), member.contactId()).orElse(null);
        boolean you = member.userId().equals(workspace.userId());
        String role = ProfileScreenController.roleName(member.role());
        if (contact == null) {
            return new Member("", null, null, role, you);
        }
        String name = (contact.givenName() + " " + contact.surname()).trim();
        return new Member(letter(contact.givenName()) + letter(contact.surname()),
            name.isBlank() ? null : name, contact.email(), role, you);
    }

    /** Blank rather than an exception for an absent or empty name half — a contact may have one. */
    private static String letter(String name) {
        return name == null || name.isBlank() ? "" : name.substring(0, 1).toUpperCase();
    }
}
