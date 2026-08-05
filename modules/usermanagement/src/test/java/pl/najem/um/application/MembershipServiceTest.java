package pl.najem.um.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class MembershipServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);

    static JdbcTemplate jdbc;
    static UserService users;
    static MembershipService memberships;
    static WorkspaceAccess access;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        users = new UserService(store, jdbc);
        memberships = new MembershipService(store, jdbc);
        access = new WorkspaceAccess(jdbc);
    }

    /** Seeds a workspace row directly; WorkspaceService lands with the WorkspaceCreatedEvent contract. */
    private static UUID workspace(String name) {
        var workspaceId = UUID.randomUUID();
        jdbc.update("insert into um_workspace(workspace_id, name, created_on) values (?,?,?)",
            workspaceId, name, TODAY);
        return workspaceId;
    }

    private record Member(UUID userId, UUID subject) {}

    private static Member member(UUID workspaceId, Role role) {
        var subject = UUID.randomUUID();
        var userId = users.register(subject, TODAY);
        jdbc.update("insert into um_membership(workspace_id, user_id, role, joined_on) values (?,?,?,?)",
            workspaceId, userId, role.name(), TODAY);
        return new Member(userId, subject);
    }

    @Test
    void roleChangeIsVisibleThroughTheAccessReadModel() {
        var workspaceId = workspace("Agencja Role");
        member(workspaceId, Role.ADMIN);
        var manager = member(workspaceId, Role.MANAGER);

        memberships.changeRole(workspaceId, manager.userId(), Role.ADMIN, LocalDate.of(2026, 8, 5));

        assertThat(access.roleIn(manager.subject(), workspaceId)).contains(Role.ADMIN);
    }

    @Test
    void roleChangeIsRecordedAsAnEventOnTheWorkspaceStream() {
        var workspaceId = workspace("Agencja Audyt");
        member(workspaceId, Role.ADMIN);
        var manager = member(workspaceId, Role.MANAGER);

        memberships.changeRole(workspaceId, manager.userId(), Role.ADMIN, LocalDate.of(2026, 8, 5));

        var types = jdbc.queryForList(
            "select event_type from events where stream_id = ? order by version", String.class, workspaceId);
        assertThat(types).containsExactly("MemberRoleChanged");
    }

    @Test
    void removedMemberLosesAccess() {
        var workspaceId = workspace("Agencja Remove");
        member(workspaceId, Role.ADMIN);
        var leaving = member(workspaceId, Role.MANAGER);

        memberships.remove(workspaceId, leaving.userId(), LocalDate.of(2026, 8, 5));

        assertThat(access.canAccess(leaving.subject(), workspaceId)).isFalse();
    }

    @Test
    void theLastAdminCannotBeRemoved() {
        var workspaceId = workspace("Agencja Ostatni");
        var soleAdmin = member(workspaceId, Role.ADMIN);

        assertThatThrownBy(() -> memberships.remove(workspaceId, soleAdmin.userId(), LocalDate.of(2026, 8, 5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theLastAdminCannotBeDemoted() {
        var workspaceId = workspace("Agencja Demote");
        var soleAdmin = member(workspaceId, Role.ADMIN);

        assertThatThrownBy(() ->
            memberships.changeRole(workspaceId, soleAdmin.userId(), Role.MANAGER, LocalDate.of(2026, 8, 5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNonMemberCannotBeChanged() {
        var workspaceId = workspace("Agencja Obca");
        var stranger = member(workspace("Agencja Inna"), Role.ADMIN);

        assertThatThrownBy(() ->
            memberships.changeRole(workspaceId, stranger.userId(), Role.MANAGER, LocalDate.of(2026, 8, 5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accessNeverLeaksAcrossWorkspaces() {
        var mine = workspace("Agencja Moja");
        var theirs = workspace("Agencja Obca 2");
        var me = member(mine, Role.MANAGER);
        member(theirs, Role.MANAGER);

        assertThat(access.canAccess(me.subject(), theirs)).isFalse();
        assertThat(access.forSubject(me.subject()))
            .extracting(WorkspaceAccess.Membership::workspaceId)
            .containsExactly(mine);
    }

    /**
     * The screens must name the agency rather than print its UUID, so the name has to survive the
     * read. The second assertion is the one that matters: the name arrives via a join, and a join
     * that matches nothing drops the whole membership — which would read as "this user belongs to
     * no agency" and be refused, not as a missing label.
     */
    @Test
    void aMembershipCarriesTheAgencysNameAndNotOnlyItsId() {
        var workspaceId = workspace("Agencja Nazwana");
        var me = member(workspaceId, Role.MANAGER);

        assertThat(access.forSubject(me.subject()))
            .singleElement()
            .satisfies(m -> {
                assertThat(m.name()).isEqualTo("Agencja Nazwana");
                assertThat(m.workspaceId()).isEqualTo(workspaceId);
            });
    }

    @Test
    void anUnknownSubjectHasNoAccessAtAll() {
        assertThat(access.forSubject(UUID.randomUUID())).isEmpty();
    }
}
