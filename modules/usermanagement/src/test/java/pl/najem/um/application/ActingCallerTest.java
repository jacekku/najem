package pl.najem.um.application;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import pl.najem.um.domain.Role;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The authorization rules, with no container and no Spring context.
 *
 * <p>This is the tier the {@link AuthenticatedSubject} port bought. The rule used to live in two
 * places — {@code CurrentUser.requireRole} and {@code WorkspaceCaller.requireRoleOf} — and only the
 * first had a test, so the copy that ran for every person who signed in through a browser was
 * unverified. There is one implementation now, and this is it.
 *
 * <p><b>What this file cannot prove</b> is that {@code @PreAuthorize} is actually attached to
 * anything. These are direct calls; method security is proxy-based and does not apply. That is
 * {@code MethodSecurityWiringTest}'s job, and the split is deliberate rather than an oversight.
 */
class ActingCallerTest {

    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    private final UserService users = mock(UserService.class);
    private final WorkspaceAccess access = mock(WorkspaceAccess.class);

    private ActingCaller caller(Optional<UUID> subject, Optional<PlatformOperator> operator) {
        return new ActingCaller(() -> subject, operator, users, access);
    }

    private ActingCaller signedIn() {
        return caller(Optional.of(SUBJECT), Optional.empty());
    }

    // ---- who is acting ----------------------------------------------------

    @Test
    void resolvesTheSignedInSubjectToItsNajemUser() {
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.of(USER_ID));

        assertThat(signedIn().actingUserId()).isEqualTo(USER_ID);
    }

    /** A valid token is not an account. Invitations create accounts; arriving with one does not. */
    @Test
    void avalidSubjectWithNoAccountIsNotACaller() {
        when(users.findBySubject(SUBJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> signedIn().actingUserId())
            .isInstanceOf(NotInvitedException.class);
    }

    @Test
    void nocallerAndNoOperatorIsDeniedRatherThanAllowed() {
        assertThatThrownBy(() -> caller(Optional.empty(), Optional.empty()).actingUserId())
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void nocallerFallsBackToTheConfiguredOperator() {
        var operator = mock(PlatformOperator.class);
        when(operator.userId()).thenReturn(USER_ID);

        assertThat(caller(Optional.empty(), Optional.of(operator)).actingUserId()).isEqualTo(USER_ID);
    }

    // ---- what they may do -------------------------------------------------

    @Test
    void anadminOfTheWorkspaceMayAdministerIt() {
        when(access.roleIn(SUBJECT, WORKSPACE)).thenReturn(Optional.of(Role.ADMIN));

        assertThat(signedIn().isAdminOf(WORKSPACE)).isTrue();
    }

    /** The rule that had no test on the path people actually used. */
    @Test
    void amemberWithoutTheRequiredRoleMayNot() {
        when(access.roleIn(SUBJECT, WORKSPACE)).thenReturn(Optional.of(Role.MANAGER));

        assertThat(signedIn().isAdminOf(WORKSPACE)).isFalse();
    }

    @Test
    void anonMemberOfTheWorkspaceMayNot() {
        when(access.roleIn(SUBJECT, WORKSPACE)).thenReturn(Optional.empty());

        assertThat(signedIn().isAdminOf(WORKSPACE)).isFalse();
    }

    @Test
    void holdingAnyOneOfTheAllowedRolesIsEnough() {
        when(access.roleIn(SUBJECT, WORKSPACE)).thenReturn(Optional.of(Role.MANAGER));

        assertThat(signedIn().hasRoleIn(WORKSPACE, Role.ADMIN, Role.MANAGER)).isTrue();
    }

    /**
     * An ADMIN of one agency is nobody in another. The role is looked up per workspace, so this
     * cannot pass by holding a role somewhere else.
     */
    @Test
    void anadminOfAnotherAgencyIsNotAnAdminHere() {
        when(access.roleIn(SUBJECT, WORKSPACE)).thenReturn(Optional.of(Role.ADMIN));

        assertThat(signedIn().isAdminOf(UUID.randomUUID())).isFalse();
    }

    /**
     * Permit-all: no subject, so no membership row to check against. The operator is allowed
     * through unchecked, which is what the previous code did and is written down rather than
     * discovered — see ActingCaller's javadoc.
     */
    @Test
    void theoperatorIsAllowedThroughWithoutARoleUnderPermitAll() {
        assertThat(caller(Optional.empty(), Optional.of(mock(PlatformOperator.class)))
            .isAdminOf(WORKSPACE)).isTrue();
    }

    /** Security disabled and no operator configured is not an open door. */
    @Test
    void nooperatorUnderPermitAllMeansNobodyMayAdminister() {
        assertThat(caller(Optional.empty(), Optional.empty()).isAdminOf(WORKSPACE)).isFalse();
    }
}
