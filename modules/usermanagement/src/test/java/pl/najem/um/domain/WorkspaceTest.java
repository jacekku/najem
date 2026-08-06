package pl.najem.um.domain;

import org.junit.jupiter.api.Test;
import pl.najem.um.domain.events.InvitationAccepted;
import pl.najem.um.domain.events.InvitationRevoked;
import pl.najem.um.domain.events.MemberInvited;
import pl.najem.um.domain.events.MemberJoined;
import pl.najem.um.domain.events.MemberRemoved;
import pl.najem.um.domain.events.MemberRoleChanged;
import pl.najem.um.domain.events.WorkspaceCreated;
import pl.najem.um.domain.events.WorkspaceEvent;
import pl.najem.um.domain.events.WorkspaceRenamed;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agency's rules, in milliseconds and with no database.
 *
 * <p>Most of what is asserted here had no fast test at all until membership moved into the
 * aggregate: "the last admin may not be removed" and "a stranger is not a member" were SQL, so the
 * only way to exercise them was to boot a Postgres container. They are the invariants that decide
 * whether an agency can still be administered, which is a poor thing to check only at merge.
 */
class WorkspaceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);
    private static final LocalDate LATER = LocalDate.of(2026, 8, 5);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 17);

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID founder = UUID.randomUUID();

    private Workspace agency;

    /**
     * One instance for the whole test, because a command applies what it decided.
     *
     * <p>This used to keep a list of events and rebuild the aggregate after every step, which was
     * scaffolding for the bug rather than for the test: the commands returned events without
     * applying them, so the only way to see the effect of one was to replay the lot.
     */
    private Workspace agency() {
        if (agency == null) {
            agency = Workspace.from(Workspace.create(workspaceId, "Agencja Krakowska", founder, TODAY));
        }
        return agency;
    }

    // ---- naming -----------------------------------------------------------

    @Test
    void creationRecordsTheAgencyNameAndItsFoundingAdmin() {
        var events = Workspace.create(workspaceId, "Agencja Krakowska", founder, TODAY);

        assertThat(events).containsExactly(
            new WorkspaceCreated(workspaceId, "Agencja Krakowska", TODAY),
            new MemberJoined(workspaceId, founder, Role.ADMIN, TODAY));
    }

    /**
     * NAJEM is invite-only and only an ADMIN can invite, so an agency with no founding admin could
     * never be joined by anybody. Before this the membership was a bare insert in the service with
     * no event behind it — the founder was a member the stream had no record of.
     */
    @Test
    void anAgencyCannotBeFoundedByNobody() {
        assertThatThrownBy(() -> Workspace.create(workspaceId, "Agencja Krakowska", null, TODAY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> Workspace.create(workspaceId, "  ", founder, TODAY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renamingReplaysIntoTheNewName() {
        var events = agency().rename("Nowa Nazwa", LATER);

        assertThat(events).containsExactly(new WorkspaceRenamed(workspaceId, "Nowa Nazwa", LATER));
        assertThat(agency().name()).isEqualTo("Nowa Nazwa");
    }

    // ---- membership -------------------------------------------------------

    @Test
    void thefounderIsAnAdminOfTheirOwnAgency() {
        assertThat(agency().roleOf(founder)).contains(Role.ADMIN);
    }

    @Test
    void astrangerIsNotAMember() {
        assertThat(agency().roleOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void astrangersRoleCannotBeChanged() {
        assertThatThrownBy(() -> agency().changeRole(UUID.randomUUID(), Role.MANAGER, LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a member");
    }

    @Test
    void aroleChangeReplaysIntoTheNewRole() {
        var manager = joined(Role.MANAGER);

        agency().changeRole(manager, Role.ADMIN, LATER);

        assertThat(agency().roleOf(manager)).contains(Role.ADMIN);
    }

    @Test
    void aremovedMemberIsNoLongerAMember() {
        var manager = joined(Role.MANAGER);

        agency().removeMember(manager, LATER);

        assertThat(agency().roleOf(manager)).isEmpty();
        assertThat(agency().members()).containsOnlyKeys(founder);
    }

    // ---- the last admin ---------------------------------------------------

    @Test
    void thelastAdminCannotBeRemoved() {
        assertThatThrownBy(() -> agency().removeMember(founder, LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least one admin");
    }

    @Test
    void thelastAdminCannotBeDemoted() {
        assertThatThrownBy(() -> agency().changeRole(founder, Role.MANAGER, LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least one admin");
    }

    /** A manager is not an admin, so removing them cannot take the last one away. */
    @Test
    void amanagerMayBeRemovedWhileTheSoleAdminRemains() {
        var manager = joined(Role.MANAGER);

        assertThat(agency().removeMember(manager, LATER))
            .containsExactly(new MemberRemoved(workspaceId, manager, LATER));
    }

    @Test
    void anadminMayBeRemovedOnceThereIsAnother() {
        joined(Role.ADMIN);

        assertThat(agency().removeMember(founder, LATER))
            .containsExactly(new MemberRemoved(workspaceId, founder, LATER));
    }

    /**
     * Promoting to ADMIN is exempt on purpose: the check exists to keep an agency administrable, and
     * making somebody an admin cannot fail that. Without the exemption the sole admin could not be
     * re-confirmed in their own role.
     */
    @Test
    void thelastAdminMayBeSetToAdminAgain() {
        assertThat(agency().changeRole(founder, Role.ADMIN, LATER))
            .containsExactly(new MemberRoleChanged(workspaceId, founder, Role.ADMIN, LATER));
    }

    // ---- invitations ------------------------------------------------------

    @Test
    void aninvitationCannotExpireBeforeItIsIssued() {
        assertThatThrownBy(() ->
            agency().invite(UUID.randomUUID(), Role.MANAGER, founder, LATER, TODAY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptingAnInvitationSeatsTheMemberAtTheInvitedRole() {
        var invitationId = UUID.randomUUID();
        var invitee = UUID.randomUUID();
        agency().invite(invitationId, Role.MANAGER, founder, TODAY, EXPIRY);

        agency().acceptInvitation(invitationId, invitee, LATER);

        assertThat(agency().roleOf(invitee)).contains(Role.MANAGER);
    }

    /**
     * A command applies what it decided, so the next one sees it.
     *
     * <p>The invariant, pinned on its own rather than left implicit in the tests that happen to
     * depend on it. Without it, inviting and then accepting through one instance fails: the second
     * command looks for an invitation the first only ever <em>returned</em>, and the aggregate that
     * has to find it never heard about it. Every rule here that reads state a previous command wrote
     * — the last-admin check after a role change, a second acceptance — rests on this.
     */
    @Test
    void acommandSeesWhatThePreviousOneDecided() {
        var invitationId = UUID.randomUUID();
        var invitee = UUID.randomUUID();

        agency().invite(invitationId, Role.MANAGER, founder, TODAY, EXPIRY);
        agency().acceptInvitation(invitationId, invitee, LATER);
        agency().changeRole(invitee, Role.ADMIN, LATER);
        agency().removeMember(founder, LATER);

        assertThat(agency().members()).isEqualTo(Map.of(invitee, Role.ADMIN));
    }

    @Test
    void aninvitationCannotBeAcceptedTwice() {
        var invitationId = UUID.randomUUID();
        agency().invite(invitationId, Role.MANAGER, founder, TODAY, EXPIRY);
        agency().acceptInvitation(invitationId, UUID.randomUUID(), LATER);

        assertThatThrownBy(() -> agency().acceptInvitation(invitationId, UUID.randomUUID(), LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no pending invitation");
    }

    @Test
    void arevokedInvitationCannotBeAccepted() {
        var invitationId = UUID.randomUUID();
        agency().invite(invitationId, Role.MANAGER, founder, TODAY, EXPIRY);
        agency().revokeInvitation(invitationId, LATER);

        assertThatThrownBy(() -> agency().acceptInvitation(invitationId, UUID.randomUUID(), LATER))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anexpiredInvitationCannotBeAccepted() {
        var invitationId = UUID.randomUUID();
        agency().invite(invitationId, Role.MANAGER, founder, TODAY, EXPIRY);

        assertThatThrownBy(() ->
            agency().acceptInvitation(invitationId, UUID.randomUUID(), EXPIRY.plusDays(1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("expired");
    }

    /** Accepting on the expiry date itself is still accepting: the invitation expires after it. */
    @Test
    void aninvitationMayBeAcceptedOnItsLastDay() {
        var invitationId = UUID.randomUUID();
        var invitee = UUID.randomUUID();
        agency().invite(invitationId, Role.MANAGER, founder, TODAY, EXPIRY);

        assertThat(agency().acceptInvitation(invitationId, invitee, EXPIRY))
            .containsExactly(
                new InvitationAccepted(workspaceId, invitationId, invitee, EXPIRY),
                new MemberJoined(workspaceId, invitee, Role.MANAGER, EXPIRY));
    }

    /**
     * An agency holds only its own invitations, so an id belonging to somebody else's agency is
     * simply unknown here — which is what makes not-found and not-yours the same answer without
     * the caller arranging it. Telling them apart confirms that an invitation exists in an agency
     * they cannot see.
     */
    @Test
    void aninvitationFromAnotherAgencyIsUnknownHere() {
        agency();

        assertThatThrownBy(() -> agency().revokeInvitation(UUID.randomUUID(), LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no pending invitation");
    }

    @Test
    void invitingRecordsWhoInvitedAndWhen() {
        var invitationId = UUID.randomUUID();

        assertThat(agency().invite(invitationId, Role.MANAGER, founder, TODAY, EXPIRY))
            .containsExactly(
                new MemberInvited(workspaceId, invitationId, Role.MANAGER, founder, TODAY, EXPIRY));
    }

    @Test
    void revokingRecordsIt() {
        var invitationId = UUID.randomUUID();
        agency().invite(invitationId, Role.MANAGER, founder, TODAY, EXPIRY);

        assertThat(agency().revokeInvitation(invitationId, LATER))
            .containsExactly(new InvitationRevoked(workspaceId, invitationId, LATER));
    }

    /** Through the real path, so the seeding exercises the same code every caller does. */
    private UUID joined(Role role) {
        var invitationId = UUID.randomUUID();
        var member = UUID.randomUUID();
        agency().invite(invitationId, role, founder, TODAY, EXPIRY);
        agency().acceptInvitation(invitationId, member, TODAY);
        return member;
    }
}
