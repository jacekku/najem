package pl.najem.um.adapter.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.domain.Role;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentUserTest {

    private static Jwt jwtFor(UUID subject) {
        return Jwt.withTokenValue("token").header("alg", "none")
            .claim("sub", subject.toString())
            .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(3600))
            .build();
    }

    @Test
    void resolvesTheSubjectClaim() {
        var subject = UUID.randomUUID();
        var currentUser = new CurrentUser(mock(UserService.class), mock(WorkspaceAccess.class));

        assertThat(currentUser.subject(jwtFor(subject))).isEqualTo(subject);
    }

    @Test
    void rejectsASubjectWithNoNajemUser() {
        var subject = UUID.randomUUID();
        var users = mock(UserService.class);
        when(users.findBySubject(subject)).thenReturn(Optional.empty());
        var currentUser = new CurrentUser(users, mock(WorkspaceAccess.class));

        assertThatThrownBy(() -> currentUser.requireUserId(jwtFor(subject)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsANonMemberOfTheWorkspace() {
        var subject = UUID.randomUUID();
        var workspaceId = UUID.randomUUID();
        var access = mock(WorkspaceAccess.class);
        when(access.roleIn(subject, workspaceId)).thenReturn(Optional.empty());
        var currentUser = new CurrentUser(mock(UserService.class), access);

        assertThatThrownBy(() -> currentUser.requireRole(jwtFor(subject), workspaceId, Role.ADMIN))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsAMemberWithoutTheRequiredRole() {
        var subject = UUID.randomUUID();
        var workspaceId = UUID.randomUUID();
        var access = mock(WorkspaceAccess.class);
        when(access.roleIn(subject, workspaceId)).thenReturn(Optional.of(Role.MANAGER));
        var currentUser = new CurrentUser(mock(UserService.class), access);

        assertThatThrownBy(() -> currentUser.requireRole(jwtFor(subject), workspaceId, Role.ADMIN))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allowsAMemberHoldingOneOfTheAllowedRoles() {
        var subject = UUID.randomUUID();
        var workspaceId = UUID.randomUUID();
        var access = mock(WorkspaceAccess.class);
        when(access.roleIn(subject, workspaceId)).thenReturn(Optional.of(Role.MANAGER));
        var currentUser = new CurrentUser(mock(UserService.class), access);

        currentUser.requireRole(jwtFor(subject), workspaceId, Role.ADMIN, Role.MANAGER);
    }

    @Test
    void aMissingTokenIsDeniedRatherThanCrashing() {
        var currentUser = new CurrentUser(mock(UserService.class), mock(WorkspaceAccess.class));

        assertThatThrownBy(() -> currentUser.requireUserId(null))
            .isInstanceOf(AccessDeniedException.class);
    }
}
