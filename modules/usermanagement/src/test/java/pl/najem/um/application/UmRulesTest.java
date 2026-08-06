package pl.najem.um.application;

import org.junit.jupiter.api.Test;
import pl.najem.um.domain.Role;
import pl.najem.um.domain.Workspace;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The usermanagement services against in-memory doubles: the fast mirror of the Testcontainers
 * suite, in milliseconds rather than minutes.
 *
 * <p><b>When this and the Postgres suite disagree, the database is right and this is wrong.</b> The
 * doubles model what the statements do — the membership upsert, the left join onto the recipient
 * lookaside, the unique constraints — but a fake can only ever be a claim about SQL, and the SQL is
 * the thing that runs. Fix the double, not the assertion (rule 16).
 *
 * <p><b>Nothing here is behind a security proxy.</b> These services are built with {@code new}, so
 * their {@code @PreAuthorize} does not fire. Everything below is a rule; none of it is a permission.
 * {@code MethodSecurityWiringTest} covers those.
 */
class UmRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);
    private static final LocalDate LATER = LocalDate.of(2026, 8, 5);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 17);

    private final InMemoryEventStore store = new InMemoryEventStore();
    private final InMemoryUsers userRows = new InMemoryUsers();
    private final InMemoryWorkspaces workspaceRows = new InMemoryWorkspaces();
    private final InMemoryMemberships membershipRows = new InMemoryMemberships(userRows, workspaceRows);
    private final InMemoryInvitations invitationRows = new InMemoryInvitations();

    /** One stable subject per email, as Keycloak would be. */
    private final Map<String, UUID> keycloak = new LinkedHashMap<>();

    private final UserService users = new UserService(store, userRows);
    private final WorkspaceService workspaces =
        new WorkspaceService(store, workspaceRows, membershipRows, userRows);
    private final MembershipService memberships = new MembershipService(store, membershipRows);
    private final InvitationService invitations = new InvitationService(store, invitationRows,
        membershipRows, users, email -> keycloak.computeIfAbsent(email, e -> UUID.randomUUID()));
    private final WorkspaceAccess access = new WorkspaceAccess(membershipRows, userRows);

    private record Agency(UUID workspaceId, UUID founderId, UUID founderSubject) {}

    private Agency agency(String name) {
        var subject = UUID.randomUUID();
        var founderId = users.register(subject, TODAY);
        return new Agency(workspaces.create(name, founderId, TODAY), founderId, subject);
    }

    // ---- creating ---------------------------------------------------------

    @Test
    void creatingAnAgencySeatsItsFounderAsAdmin() {
        var agency = agency("Agencja Krakowska");

        assertThat(access.roleIn(agency.founderSubject(), agency.workspaceId())).contains(Role.ADMIN);
    }

    @Test
    void anUnregisteredCreatorIsRefused() {
        assertThatThrownBy(() -> workspaces.create("Agencja Widmo", UUID.randomUUID(), TODAY))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a registered user");
    }

    @Test
    void renamingIsVisibleToTheScreens() {
        var agency = agency("Stara Nazwa");

        workspaces.rename(agency.workspaceId(), "Nowa Nazwa", LATER);

        assertThat(access.forSubject(agency.founderSubject()))
            .singleElement()
            .satisfies(m -> assertThat(m.name()).isEqualTo("Nowa Nazwa"));
    }

    // ---- inviting and accepting -------------------------------------------

    @Test
    void acceptingAnInvitationCreatesTheAccountAndTheMembership() {
        var agency = agency("Agencja Zapraszajaca");

        var issued = invitations.invite(agency.workspaceId(), "nowy@example.com", Role.MANAGER,
            agency.founderId(), TODAY, EXPIRY);
        var userId = invitations.accept(issued.token(), LATER);

        assertThat(membershipRows.roleOf(agency.workspaceId(), userId)).contains(Role.MANAGER);
    }

    @Test
    void anUnknownTokenIsRefused() {
        assertThatThrownBy(() -> invitations.accept("nie-ma-takiego", LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown invitation token");
    }

    /**
     * The plaintext token is never stored. If this ever passes by finding the token itself in the
     * repository, D4 has been lost.
     */
    @Test
    void theplaintextTokenIsNotWhatIsStored() {
        var agency = agency("Agencja Token");

        var issued = invitations.invite(agency.workspaceId(), "token@example.com", Role.MANAGER,
            agency.founderId(), TODAY, EXPIRY);

        assertThat(invitationRows.byTokenHash(issued.token())).isEmpty();
        assertThat(invitationRows.byTokenHash(InvitationService.hash(issued.token()))).isPresent();
    }

    @Test
    void aninvitationCannotBeAcceptedTwice() {
        var agency = agency("Agencja Raz");
        var issued = invitations.invite(agency.workspaceId(), "raz@example.com", Role.MANAGER,
            agency.founderId(), TODAY, EXPIRY);
        invitations.accept(issued.token(), LATER);

        assertThatThrownBy(() -> invitations.accept(issued.token(), LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no pending invitation");
    }

    /**
     * The reason {@code requireInvitationOpen} is asked before Keycloak is called. A second
     * acceptance used to reach the identity provider first and then fail on the deleted recipient
     * row, reporting "no recipient on record" — which reads as corrupt data rather than a spent link.
     */
    @Test
    void asecondAcceptanceNeverReachesTheIdentityProvider() {
        var agency = agency("Agencja Idp");
        var issued = invitations.invite(agency.workspaceId(), "idp@example.com", Role.MANAGER,
            agency.founderId(), TODAY, EXPIRY);
        invitations.accept(issued.token(), LATER);
        int provisionedOnce = keycloak.size();

        assertThatThrownBy(() -> invitations.accept(issued.token(), LATER))
            .hasMessageContaining("no pending invitation");
        assertThat(keycloak).hasSize(provisionedOnce);
    }

    @Test
    void arevokedInvitationCannotBeAccepted() {
        var agency = agency("Agencja Odwolana");
        var issued = invitations.invite(agency.workspaceId(), "odwolany@example.com", Role.MANAGER,
            agency.founderId(), TODAY, EXPIRY);

        invitations.revoke(agency.workspaceId(), issued.invitationId(), LATER);

        assertThatThrownBy(() -> invitations.accept(issued.token(), LATER))
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The cross-agency revoke defect, kept as a test. An admin of one agency holding another's
     * invitation id used to revoke it, with nothing on the victim's stream to say why their
     * invitation had stopped working.
     */
    @Test
    void aninvitationCannotBeRevokedFromAnotherAgency() {
        var mine = agency("Agencja Moja");
        var theirs = agency("Agencja Obca");
        var issued = invitations.invite(theirs.workspaceId(), "ich@example.com", Role.MANAGER,
            theirs.founderId(), TODAY, EXPIRY);

        assertThatThrownBy(() ->
            invitations.revoke(mine.workspaceId(), issued.invitationId(), LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no pending invitation");

        // And it still works for the agency that owns it.
        assertThat(invitations.accept(issued.token(), LATER)).isNotNull();
    }

    @Test
    void anexpiredInvitationCannotBeAccepted() {
        var agency = agency("Agencja Wygasla");
        var issued = invitations.invite(agency.workspaceId(), "stary@example.com", Role.MANAGER,
            agency.founderId(), TODAY, EXPIRY);

        assertThatThrownBy(() -> invitations.accept(issued.token(), EXPIRY.plusDays(1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expired");
    }

    /** One person, two agencies: accepting twice reuses the account rather than making a second. */
    @Test
    void thesamePersonInvitedTwiceGetsOneAccount() {
        var first = agency("Agencja Pierwsza");
        var second = agency("Agencja Druga");
        var a = invitations.invite(first.workspaceId(), "oboje@example.com", Role.MANAGER,
            first.founderId(), TODAY, EXPIRY);
        var b = invitations.invite(second.workspaceId(), "oboje@example.com", Role.ADMIN,
            second.founderId(), TODAY, EXPIRY);

        var userId = invitations.accept(a.token(), LATER);

        assertThat(invitations.accept(b.token(), LATER)).isEqualTo(userId);
        assertThat(access.membershipsOfUser(userId))
            .extracting(WorkspaceAccess.Membership::workspaceId)
            .containsExactlyInAnyOrder(first.workspaceId(), second.workspaceId());
    }

    // ---- membership -------------------------------------------------------

    @Test
    void thelastAdminCannotBeRemoved() {
        var agency = agency("Agencja Ostatni");

        assertThatThrownBy(() ->
            memberships.remove(agency.workspaceId(), agency.founderId(), LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least one admin");
    }

    @Test
    void aremovedMemberLosesAccess() {
        var agency = agency("Agencja Usuwajaca");
        var issued = invitations.invite(agency.workspaceId(), "odchodzi@example.com", Role.MANAGER,
            agency.founderId(), TODAY, EXPIRY);
        var userId = invitations.accept(issued.token(), LATER);

        memberships.remove(agency.workspaceId(), userId, LATER);

        assertThat(membershipRows.roleOf(agency.workspaceId(), userId)).isEmpty();
    }

    @Test
    void accessNeverLeaksAcrossAgencies() {
        var mine = agency("Agencja Moja 2");
        var theirs = agency("Agencja Obca 2");

        assertThat(access.canAccess(mine.founderSubject(), theirs.workspaceId())).isFalse();
        assertThat(access.canAccess(mine.founderSubject(), mine.workspaceId())).isTrue();
    }

    // ---- the projection must agree with the aggregate ---------------------

    /**
     * The tripwire for the exception {@link MembershipProjection} takes to rule A7.
     *
     * <p>{@code ActingCaller} answers {@code @PreAuthorize} from the projection, not from the
     * stream, because replaying a workspace on every request is not viable. That is only safe while
     * the two agree. This drives a whole membership lifecycle through the services and then compares
     * what the table says against what the events replay to.
     *
     * <p><b>If this goes red, it is an authorization bug, not a reporting one</b> — somebody is being
     * granted or refused a role the record does not give them. Do not relax it to compare only the
     * users and not their roles; the role is the part that decides.
     */
    @Test
    void themembershipProjectionAgreesWithTheAggregate() {
        var agency = agency("Agencja Zgodna");

        var promoted = invitations.accept(invitations.invite(agency.workspaceId(),
            "awans@example.com", Role.MANAGER, agency.founderId(), TODAY, EXPIRY).token(), LATER);
        var leaving = invitations.accept(invitations.invite(agency.workspaceId(),
            "odchodzi@example.com", Role.MANAGER, agency.founderId(), TODAY, EXPIRY).token(), LATER);
        var revoked = invitations.invite(agency.workspaceId(), "nigdy@example.com", Role.MANAGER,
            agency.founderId(), TODAY, EXPIRY);

        memberships.changeRole(agency.workspaceId(), promoted, Role.ADMIN, LATER);
        memberships.remove(agency.workspaceId(), leaving, LATER);
        invitations.revoke(agency.workspaceId(), revoked.invitationId(), LATER);

        var replayed = Workspace.from(
            UmStreams.workspaceEvents(store.load(agency.workspaceId(), "Workspace"))).members();

        // Pinned, so that the two agreeing on the wrong thing still fails. Comparing the projection
        // against the aggregate alone would pass if a bug dropped the same member from both.
        assertThat(replayed).isEqualTo(Map.of(
            agency.founderId(), Role.ADMIN,
            promoted, Role.ADMIN));
        assertThat(membershipRows.rolesIn(agency.workspaceId())).isEqualTo(replayed);
        assertThat(leaving).isNotIn(replayed.keySet());
    }
}
