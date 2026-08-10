package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.najem.app.SharedDatabase;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactService;
import pl.najem.um.application.InvitationService;
import pl.najem.um.application.KeycloakAdminPort;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mój profil, Ustawienia agencji, and the account menu that reaches them.
 *
 * <p>Container tier, because the fact worth covering is a join only a running stack performs: a
 * NAJEM account carries a Keycloak subject and a contact id and NO personal data (D1), so a name on
 * either screen is usermanagement's row joined to a contacts row. That join is new — {@code
 * um_user.contact_id} had a writer and no reader until this branch — and a screen that renders a
 * blank where a name belongs looks identical to a screen with nothing to render.
 *
 * <p>Its own operator subject, as every application test has: memberships resolve per subject and
 * two classes sharing one pool their agencies ({@code SharedDatabaseIsolationTest}).
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + AccountScreensTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Tag("integration")
class AccountScreensTest extends SharedDatabase {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000080";

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired InvitationService invitations;
    @Autowired ContactService contacts;

    /**
     * The one bean that cannot be real here.
     *
     * <p>Accepting an invitation provisions the invitee in Keycloak, and no identity provider is
     * configured in this tier — {@code IdentityProviderConfig} deliberately supplies a port that
     * throws rather than one that silently invents accounts, which is the behaviour roadmap rule 7
     * asks for and not something to route around by pointing the test at a URL. Everything after
     * the provision call is this application's own, and that is what is under test.
     */
    @MockBean KeycloakAdminPort keycloak;

    /** Static: JUnit builds a new instance per method, so an instance guard would never be false. */
    static UUID agency;
    static UUID colleague;

    @BeforeEach
    void anAgencyWithTwoPeople() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency != null) {
            return;
        }
        agency = workspaces.create("Vistula Zarządzanie", operator, LocalDate.now());

        // The operator IS a person, and this is the link the two screens read. Without it both
        // render their "no linked contact" state, which is a real state and would let every name
        // assertion below pass vacuously — so linking is the fixture, not a convenience.
        UUID me = contacts.registerLead(agency,
            new ContactDetails("Marta", "Zielińska", "m.zielinska@vistula.pl", "+48 602 118 340"),
            true, LocalDate.now());
        users.linkContact(operator, me, LocalDate.now());

        // A second member, joined the way a second member actually joins — an invitation issued and
        // accepted — and deliberately WITHOUT a linked contact. An account nobody has matched to a
        // person is an ordinary state, and the row that matters most on an access list is the one
        // the application knows least about: dropping it to avoid a null would under-report who can
        // reach the agency's data.
        org.mockito.Mockito.when(keycloak.provision("p.kowalczyk@vistula.pl"))
            .thenReturn(UUID.fromString("3f1d9c22-0000-4000-8000-000000000081"));
        var invitation = invitations.invite(agency, "p.kowalczyk@vistula.pl", Role.MANAGER,
            operator, LocalDate.now(), LocalDate.now().plusDays(7));
        colleague = invitations.accept(invitation.token(), LocalDate.now());
    }

    private String render(String path) throws Exception {
        return mvc.perform(get(path))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    /** The name, e-mail and phone come from Contacts through the account's own contact link. */
    @Test
    void theProfileNamesThePersonBehindTheAccount() throws Exception {
        var html = render("/profil");

        assertThat(html).contains("Marta Zielińska");
        assertThat(html).contains("m.zielinska@vistula.pl");
        assertThat(html).contains("+48 602 118 340");
        assertThat(html).as("initials for the avatar").contains(">MZ<");
    }

    /** Every agency the person may act in, with the one they are in marked. */
    @Test
    void theProfileListsTheAgenciesAndMarksTheActiveOne() throws Exception {
        var html = render("/profil");

        assertThat(html).contains("Vistula Zarządzanie");
        assertThat(html).contains("Bieżąca");
    }

    /**
     * The team list is the reason this screen exists: the memberships were enforced on every
     * request and could not be seen anywhere.
     *
     * <p>Both rows are asserted, and the unnamed one is the assertion that matters. A member with no
     * linked contact must keep their row — an access list that silently omits whoever the
     * application cannot name is an access list that under-reports access, which is the one
     * direction it must never be wrong in.
     */
    @Test
    void theSettingsScreenListsEveryMemberIncludingTheOnesItCannotName() throws Exception {
        var html = render("/ustawienia");

        assertThat(html).contains("Marta Zielińska");
        assertThat(html).as("the acting person is marked").contains(">Ty<");
        assertThat(html).as("both roles, in Polish").contains("Administrator").contains("Zarządca");
        assertThat(html).as("the member with no contact keeps their row")
            .contains("Konto bez powiązanej osoby");
        assertThat(html).as("counted, and declined").contains("2 osoby");
    }

    /**
     * Every invented card on both screens carries the demo flag.
     *
     * <p>These two screens mix read and invented data more than any others — a real agency name
     * beside a fake NIP — and the flag is the only thing that keeps somebody from putting the fake
     * tax identity on an invoice. Counted rather than merely present: four flagged cards, and a
     * regression that drops the flag from one leaves the other three passing a presence check.
     */
    @Test
    void everyInventedCardOnBothScreensSaysSo() throws Exception {
        assertThat(flags(render("/profil")))
            .as("Hasło i logowanie, Powiadomienia").isEqualTo(2);
        assertThat(flags(render("/ustawienia")))
            .as("Dane agencji, Domyślne zasady rozliczeń, Integracje").isEqualTo(3);
    }

    private static int flags(String html) {
        return html.split("demo-flag", -1).length - 1;
    }

    /**
     * The sidebar's account menu, on an ordinary screen rather than on its own.
     *
     * <p>It is shell chrome, so the thing to check is that it renders on a page that knows nothing
     * about it — and that all three destinations are real links rather than the {@code href="#"}
     * this application never writes.
     */
    @Test
    void theAccountMenuReachesAllThreeDestinationsFromAnyScreen() throws Exception {
        var html = render("/");

        assertThat(html).contains("class=\"account-menu");
        assertThat(html).contains("href=\"/agencies\"");
        assertThat(html).contains("href=\"/profil\"");
        assertThat(html).contains("href=\"/ustawienia\"");
        // NO sign-out here, and its absence is the assertion. Under permit-all there is no session
        // to end, and the menu renders the item only when somebody is actually signed in — a
        // control that cannot do its job is worse than one that is not offered. The other half of
        // this pair is signedInPersonIsOfferedSignOutInsideTheMenu, below.
        assertThat(html).as("no session under permit-all, so nothing to sign out of")
            .doesNotContain("action=\"/logout\"");
    }

    /**
     * Somebody who IS signed in gets Wyloguj, inside the account menu.
     *
     * <p><b>This is the half that was missing, and its absence is what made "where did logout go?"
     * a question nobody could answer from the suite.</b> Sign-out moved out of the sidebar footer
     * and into the menu on this branch; the only assertion about it anywhere was the negative one
     * above, which passes just as happily if the control has been deleted outright. A guard with
     * only its false branch covered is not covered (refactoring.md rule 20).
     *
     * <p>{@code oidcLogin()} rather than a real Keycloak: {@code signedInAs} is read from the
     * {@code OidcUser} principal, and that post-processor is the supported way to put one on a
     * request. The agency still resolves, because {@code ActiveAgencyAdvice} asks for a
     * {@code Jwt} — absent here — and falls back to the configured platform operator, which is the
     * same path every other test in this class runs on.
     *
     * <p>A POST, asserted as one. A GET that ends a session is reachable by a prefetch, an image
     * tag on another site, or a browser restoring tabs.
     */
    @Test
    void asignedInPersonIsOfferedSignOutInsideTheMenu() throws Exception {
        // The operator's OWN subject, not a fresh one. ActingCaller resolves the subject to a NAJEM
        // account and refuses a token belonging to nobody — "signed in" and "has an account" are
        // different things, and invitations create accounts. A random subject here answers 403,
        // which is the resolver working correctly and not the thing under test.
        var html = mvc.perform(get("/").with(oidcLogin()
                .idToken(token -> token.subject(OPERATOR).claim("preferred_username", "marta"))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).as("a POST, never a link").contains("action=\"/logout\"");
        assertThat(html).contains("Wyloguj");
        assertThat(html).as("inside the menu, not loose in the footer it used to sit in")
            .contains("account-menu__signout");
        assertThat(html).as("and the menu names who it would sign out")
            .contains("Zalogowano jako marta");
    }
}
