package pl.najem.um.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import pl.najem.eventstore.EventStore;
import pl.najem.um.application.InvitationService;
import pl.najem.um.application.KeycloakAdminPort;
import pl.najem.um.application.MembershipService;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.application.WorkspaceService;

/**
 * Assembles the usermanagement services onto a database, for the tests that do not boot Spring.
 *
 * <p>The counterpart of {@code PostgresAccounting}, {@code PostgresPropertyManagement} and
 * {@code PostgresContacts}, written for the same reason (rule 6): the wiring is knowledge about
 * which implementation to use, so it belongs on the adapter side of the boundary rather than in a
 * convenience constructor on a service, which would have the application layer naming the adapters
 * that implement its own ports.
 *
 * <p><b>Nothing built here is behind a security proxy.</b> These are plain constructor calls, so the
 * {@code @PreAuthorize} on {@code MembershipService} and {@code InvitationService} does not fire —
 * method security is proxy-based. Tests wired from this fixture exercise rules, and a green one says
 * nothing about whether authorization works. {@code MethodSecurityWiringTest} is what covers that,
 * by booting a context. This is stated here as well as on {@code SecurityConfig} because this class
 * is where somebody is standing when the distinction stops being obvious.
 */
public final class PostgresUserManagement {

    private PostgresUserManagement() {}

    public static UserService userService(EventStore store, JdbcTemplate jdbc) {
        return new UserService(store, new PostgresUserProjection(jdbc));
    }

    public static WorkspaceService workspaceService(EventStore store, JdbcTemplate jdbc) {
        return new WorkspaceService(store, new PostgresWorkspaceProjection(jdbc),
            new PostgresMembershipProjection(jdbc), new PostgresUserProjection(jdbc));
    }

    public static MembershipService membershipService(EventStore store, JdbcTemplate jdbc) {
        return new MembershipService(store, new PostgresMembershipProjection(jdbc));
    }

    public static InvitationService invitationService(EventStore store, JdbcTemplate jdbc,
                                                      KeycloakAdminPort keycloak) {
        return new InvitationService(store, new PostgresInvitationRepository(jdbc),
            new PostgresMembershipProjection(jdbc), userService(store, jdbc), keycloak);
    }

    public static WorkspaceAccess workspaceAccess(JdbcTemplate jdbc) {
        return new WorkspaceAccess(new PostgresMembershipProjection(jdbc),
            new PostgresUserProjection(jdbc));
    }

    /**
     * The membership projection itself, exposed because the test that asserts it agrees with the
     * aggregate has to read it directly — going through {@link WorkspaceAccess} would compare the
     * aggregate against a service built on the very projection under test.
     */
    public static PostgresMembershipProjection memberships(JdbcTemplate jdbc) {
        return new PostgresMembershipProjection(jdbc);
    }
}
