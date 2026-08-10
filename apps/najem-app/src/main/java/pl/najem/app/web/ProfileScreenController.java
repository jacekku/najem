package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.domain.Role;

import java.util.List;
import java.util.UUID;

/**
 * Mój profil: who the acting person is, and which agencies they may act in.
 *
 * <p><b>The name comes from Contacts and nowhere else.</b> Usermanagement holds no personal data by
 * design (D1) — {@code um_user} carries a Keycloak subject and a contact id, and that is the whole
 * of it. So this screen resolves the acting user to a contact and asks Contacts, which is the same
 * route {@code UnitScreenController} takes for a tenant. An account with no linked contact is an
 * ordinary state (nobody has matched it to a person yet), and it renders as an unnamed profile
 * rather than as the Keycloak username wearing a person's name.
 *
 * <p><b>Two things here are real and one is not, and the template says which.</b> The identity and
 * the membership list are read; the password, the second factor and the notification preferences
 * are {@link AccountFake}, because Keycloak owns credentials and nothing in this application stores
 * a preference. Mixing them silently is how a reviewer stops trusting either — see that class.
 *
 * <p>No workspace is needed to answer "who am I", but one is taken anyway: {@link WebWorkspace}
 * carries the acting {@code userId} already resolved and already checked, and resolving the subject
 * a second time here would be a second implementation of "who is acting" (the reason
 * {@code WebWorkspaceResolver} keeps that job to itself).
 */
@Controller
public class ProfileScreenController {

    /**
     * One agency the acting person belongs to, and whether it is the one they are in right now.
     *
     * @param active the agency this request is acting in. The list is the whole point of the card —
     *               somebody who belongs to three needs to see which of the three they are looking
     *               at the rest of the application through.
     */
    public record Membership(UUID workspaceId, String name, String role, boolean active) {
    }

    /**
     * The acting person, as far as this application knows them.
     *
     * @param name    null when no contact is linked. Null rather than a placeholder, so the template
     *                decides how to render an absence once instead of every field guessing.
     */
    public record Profile(String initials, String name, String email, String phone,
                          String signedInAs) {
    }

    private final WorkspaceAccess access;
    private final ContactDirectory contacts;

    public ProfileScreenController(WorkspaceAccess access, ContactDirectory contacts) {
        this.access = access;
        this.contacts = contacts;
    }

    @GetMapping("/profil")
    public String profile(WebWorkspace workspace, Model model) {
        model.addAttribute("profile", profile(workspace));
        model.addAttribute("memberships", memberships(workspace));
        model.addAttribute("notifications", AccountFake.NOTIFICATIONS);
        model.addAttribute("twoFactorState", AccountFake.TWO_FACTOR_STATE);
        model.addAttribute("twoFactorNote", AccountFake.TWO_FACTOR_NOTE);
        model.addAttribute("passwordNote", AccountFake.PASSWORD_NOTE);
        return "profile";
    }

    private Profile profile(WebWorkspace workspace) {
        var contact = access.contactOf(workspace.userId())
            .flatMap(contactId -> contacts.find(workspace.workspaceId(), contactId))
            .orElse(null);
        if (contact == null) {
            return new Profile("", null, null, null, workspace.name());
        }
        String name = (contact.givenName() + " " + contact.surname()).trim();
        return new Profile(letter(contact.givenName()) + letter(contact.surname()),
            name.isBlank() ? null : name, contact.email(), contact.phone(), workspace.name());
    }

    /** Blank rather than an exception for an absent or empty name half — a contact may have one. */
    private static String letter(String name) {
        return name == null || name.isBlank() ? "" : name.substring(0, 1).toUpperCase();
    }

    /**
     * Every agency this person belongs to, with the active one marked.
     *
     * <p>Asked by NAJEM user id rather than by Keycloak subject: {@link WebWorkspace} already
     * carries the id, and {@code membershipsOfUser} exists precisely so a caller holding one does
     * not have to round-trip through the identity provider's notion of a person to answer a
     * question about ours.
     */
    private List<Membership> memberships(WebWorkspace workspace) {
        return access.membershipsOfUser(workspace.userId()).stream()
            .map(m -> new Membership(m.workspaceId(), m.name(), roleName(m.role()),
                m.workspaceId().equals(workspace.workspaceId())))
            .toList();
    }

    /**
     * The role as a manager says it.
     *
     * <p>A switch rather than a lookup so a third role is a compile error here instead of a screen
     * showing a landlord the constant's own SHOUTING name — the same call
     * {@code UnitScreenController.legalForm} makes, and the {@code default} is what keeps this
     * compiling while {@code Role}'s own javadoc says the enum stays open.
     */
    static String roleName(Role role) {
        return switch (role) {
            case ADMIN -> "Administrator";
            case MANAGER -> "Zarządca";
            default -> role.name();
        };
    }
}
