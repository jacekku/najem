package pl.najem.um.application;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import pl.najem.um.domain.Role;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is acting, and what they may do.
 *
 * <p>This is {@code WorkspaceCaller} with the concept named and the arrow turned round. That class
 * imported {@code adapter.security.CurrentUser} while re-implementing the half of it that could not
 * be reused — so the role check existed twice, in two layers, and only the adapter's copy had a
 * test. The copy that ran for every person who signed in through a browser had none.
 *
 * <p>The bean is named {@code caller} because {@code @PreAuthorize} refers to it by name:
 *
 * <pre>{@code @PreAuthorize("@caller.isAdminOf(#workspaceId)")}</pre>
 *
 * <p>which is the arrangement Spring Security recommends for anything past a role literal — the
 * logic sits in a class that can be unit tested on its own rather than inside an expression that
 * cannot. {@link #isAdminOf} answers <em>whether</em>, and never throws, because a
 * {@code @PreAuthorize} expression that throws reports the wrong thing.
 *
 * <p>Roles resolve from {@link WorkspaceAccess}, never from token claims (decision D1). Keycloak is
 * the identity provider; who is an ADMIN of which agency is NAJEM's own data.
 *
 * <h2>The unauthenticated operator</h2>
 *
 * <p>With security disabled there is no subject, and the only acceptable caller is the explicitly
 * configured platform operator. <b>The operator is not role-checked</b>, which is deliberate and is
 * the behaviour this class inherited: there is no membership row to check them against, and
 * permit-all is a local-and-test posture where the alternative is refusing every request. If
 * neither a subject nor an operator exists the request is denied rather than silently allowed — an
 * unauthenticated request must never end up acting as somebody.
 */
@Component("caller")
public class ActingCaller {

    private final AuthenticatedSubject authenticated;
    private final Optional<PlatformOperator> operator;
    private final UserService users;
    private final WorkspaceAccess access;

    public ActingCaller(AuthenticatedSubject authenticated, Optional<PlatformOperator> operator,
                        UserService users, WorkspaceAccess access) {
        this.authenticated = authenticated;
        this.operator = operator;
        this.users = users;
        this.access = access;
    }

    /**
     * The acting user's NAJEM id.
     *
     * <p>Signing in with a valid token is not the same as having an account: invitations create
     * accounts, and arriving with a token the identity provider happily issued does not.
     */
    public UUID actingUserId() {
        return authenticated.current()
            .map(subject -> users.findBySubject(subject)
                .orElseThrow(() -> new NotInvitedException(
                    "signed in, but this subject has no NAJEM account — invitations create accounts, "
                        + "and an account is never created by simply arriving with a valid token")))
            .orElseGet(this::unauthenticatedOperator);
    }

    /** For {@code @PreAuthorize}. Whether, not throw — an expression reports a decision. */
    public boolean isAdminOf(UUID workspaceId) {
        return hasRoleIn(workspaceId, Role.ADMIN);
    }

    /**
     * Whether the acting caller holds one of {@code allowed} in {@code workspaceId}.
     *
     * <p>Public because {@code @PreAuthorize} may need a set other than ADMIN, and because the web
     * layer asks the same question when deciding what a screen may show. One implementation of the
     * rule, which is the point of the class.
     */
    public boolean hasRoleIn(UUID workspaceId, Role... allowed) {
        var subject = authenticated.current();
        if (subject.isEmpty()) {
            // No caller at all. Allowed only if this deployment configured an operator; see the
            // class javadoc for why the operator is not role-checked.
            return operator.isPresent();
        }
        return access.roleIn(subject.get(), workspaceId)
            .filter(role -> Arrays.asList(allowed).contains(role))
            .isPresent();
    }

    private UUID unauthenticatedOperator() {
        return operator
            .orElseThrow(() -> new AccessDeniedException(
                "no authenticated caller and no configured platform operator"))
            .userId();
    }
}
