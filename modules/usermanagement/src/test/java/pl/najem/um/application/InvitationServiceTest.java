package pl.najem.um.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class InvitationServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 17);

    /** Fake identity provider: one stable subject per email, no Keycloak needed. */
    static final Map<String, UUID> KEYCLOAK = new ConcurrentHashMap<>();

    static JdbcTemplate jdbc;
    static UserService users;
    static WorkspaceService workspaces;
    static InvitationService invitations;
    static WorkspaceAccess access;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        registry.register(WorkspaceCreatedEvent.class);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        users = new UserService(store, jdbc);
        workspaces = new WorkspaceService(store, jdbc);
        access = new WorkspaceAccess(jdbc);
        invitations = new InvitationService(store, jdbc, users,
            email -> KEYCLOAK.computeIfAbsent(email, e -> UUID.randomUUID()));
    }

    /** A workspace with its founding ADMIN, the only way one legitimately comes into being. */
    private static UUID workspaceWithAdmin(String name) {
        var founder = users.register(UUID.randomUUID(), TODAY);
        return workspaces.create(name, founder, TODAY);
    }

    private static UUID admin(UUID workspaceId) {
        return jdbc.queryForObject("select user_id from um_membership where workspace_id = ? and role = 'ADMIN'",
            UUID.class, workspaceId);
    }

    @Test
    void issuedInvitationIsPendingAndStoresOnlyAHashedToken() {
        var workspaceId = workspaceWithAdmin("Agencja Testowa");

        var issued = invitations.invite(workspaceId, "nowy@example.com", Role.MANAGER,
            admin(workspaceId), TODAY, EXPIRY);

        assertThat(issued.token()).isNotBlank();
        assertThat(jdbc.queryForObject("select status from um_invitation where invitation_id = ?",
            String.class, issued.invitationId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select token_hash from um_invitation where invitation_id = ?",
            String.class, issued.invitationId()))
            .isEqualTo(InvitationService.hash(issued.token()))
            .isNotEqualTo(issued.token());
    }

    @Test
    void emailLivesOnlyInTheLookasideNeverInAnEvent() {
        var workspaceId = workspaceWithAdmin("Agencja PII");

        var issued = invitations.invite(workspaceId, "prywatny@example.com", Role.MANAGER,
            admin(workspaceId), TODAY, EXPIRY);

        assertThat(jdbc.queryForObject("select email from um_invitation_recipient where invitation_id = ?",
            String.class, issued.invitationId())).isEqualTo("prywatny@example.com");
        var payloads = jdbc.queryForList(
            "select payload::text from events where stream_id = ?", String.class, workspaceId);
        assertThat(payloads).isNotEmpty()
            .allSatisfy(p -> assertThat(p).doesNotContain("prywatny@example.com"));
    }

    @Test
    void revokingDropsTheRecipientRowAndMarksTheInvitation() {
        var workspaceId = workspaceWithAdmin("Agencja Revoke");
        var issued = invitations.invite(workspaceId, "odwolany@example.com", Role.MANAGER,
            admin(workspaceId), TODAY, EXPIRY);

        invitations.revoke(workspaceId, issued.invitationId(), LocalDate.of(2026, 8, 5));

        assertThat(jdbc.queryForObject("select status from um_invitation where invitation_id = ?",
            String.class, issued.invitationId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
            "select count(*) from um_invitation_recipient where invitation_id = ?",
            Integer.class, issued.invitationId())).isZero();
    }

    @Test
    void anInvitationCannotExpireBeforeItIsIssued() {
        var workspaceId = workspaceWithAdmin("Agencja Daty");

        assertThatThrownBy(() -> invitations.invite(workspaceId, "wstecz@example.com", Role.MANAGER,
            admin(workspaceId), TODAY, LocalDate.of(2026, 8, 2)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptingProvisionsTheUserAndGrantsTheInvitedRole() {
        var workspaceId = workspaceWithAdmin("Agencja Accept");
        var issued = invitations.invite(workspaceId, "manager@example.com", Role.MANAGER,
            admin(workspaceId), TODAY, EXPIRY);

        var userId = invitations.accept(issued.token(), LocalDate.of(2026, 8, 4));

        assertThat(access.roleIn(KEYCLOAK.get("manager@example.com"), workspaceId)).contains(Role.MANAGER);
        assertThat(jdbc.queryForObject("select keycloak_subject from um_user where user_id = ?",
            UUID.class, userId)).isEqualTo(KEYCLOAK.get("manager@example.com"));
        assertThat(jdbc.queryForObject("select status from um_invitation where invitation_id = ?",
            String.class, issued.invitationId())).isEqualTo("ACCEPTED");
    }

    @Test
    void acceptingErasesTheRecipientLookaside() {
        var workspaceId = workspaceWithAdmin("Agencja Erase");
        var issued = invitations.invite(workspaceId, "erase@example.com", Role.MANAGER,
            admin(workspaceId), TODAY, EXPIRY);

        invitations.accept(issued.token(), LocalDate.of(2026, 8, 4));

        assertThat(jdbc.queryForObject(
            "select count(*) from um_invitation_recipient where invitation_id = ?",
            Integer.class, issued.invitationId())).isZero();
    }

    @Test
    void aTokenCannotBeUsedTwice() {
        var workspaceId = workspaceWithAdmin("Agencja Reuse");
        var issued = invitations.invite(workspaceId, "reuse@example.com", Role.MANAGER,
            admin(workspaceId), TODAY, EXPIRY);
        invitations.accept(issued.token(), LocalDate.of(2026, 8, 4));

        assertThatThrownBy(() -> invitations.accept(issued.token(), LocalDate.of(2026, 8, 5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRevokedInvitationCannotBeAccepted() {
        var workspaceId = workspaceWithAdmin("Agencja Cofnieta");
        var issued = invitations.invite(workspaceId, "cofniety@example.com", Role.MANAGER,
            admin(workspaceId), TODAY, EXPIRY);
        invitations.revoke(workspaceId, issued.invitationId(), LocalDate.of(2026, 8, 4));

        assertThatThrownBy(() -> invitations.accept(issued.token(), LocalDate.of(2026, 8, 5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        var workspaceId = workspaceWithAdmin("Agencja Expiry");
        var issued = invitations.invite(workspaceId, "late@example.com", Role.MANAGER,
            admin(workspaceId), TODAY, LocalDate.of(2026, 8, 4));

        assertThatThrownBy(() -> invitations.accept(issued.token(), LocalDate.of(2026, 8, 5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownTokenIsRejectedAndCreatesNobody() {
        int usersBefore = jdbc.queryForObject("select count(*) from um_user", Integer.class);

        assertThatThrownBy(() -> invitations.accept("not-a-real-token", TODAY))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from um_user", Integer.class)).isEqualTo(usersBefore);
    }

    @Test
    void anExistingUserJoinsASecondWorkspaceWithoutReprovisioning() {
        var first = workspaceWithAdmin("Agencja Pierwsza");
        var second = workspaceWithAdmin("Agencja Druga");
        var firstInvite = invitations.invite(first, "wielo@example.com", Role.MANAGER,
            admin(first), TODAY, EXPIRY);
        var userId = invitations.accept(firstInvite.token(), LocalDate.of(2026, 8, 4));
        var secondInvite = invitations.invite(second, "wielo@example.com", Role.ADMIN,
            admin(second), TODAY, EXPIRY);

        var sameUser = invitations.accept(secondInvite.token(), LocalDate.of(2026, 8, 5));

        assertThat(sameUser).isEqualTo(userId);
        assertThat(access.forSubject(KEYCLOAK.get("wielo@example.com")))
            .extracting(WorkspaceAccess.Membership::workspaceId)
            .containsExactlyInAnyOrder(first, second);
    }
}
