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
import pl.najem.eventstore.EventStore;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;
import pl.najem.um.adapter.persistence.PostgresMembershipProjection;
import pl.najem.um.adapter.persistence.PostgresUserManagement;
import pl.najem.um.domain.Role;
import pl.najem.um.domain.events.MemberJoined;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class MembershipServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);
    private static final LocalDate LATER = LocalDate.of(2026, 8, 5);

    static JdbcTemplate jdbc;
    static EventStore store;
    static UserService users;
    static WorkspaceService workspaces;
    static MembershipService memberships;
    static WorkspaceAccess access;
    static PostgresMembershipProjection projection;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        users = PostgresUserManagement.userService(store, jdbc);
        workspaces = PostgresUserManagement.workspaceService(store, jdbc);
        memberships = PostgresUserManagement.membershipService(store, jdbc);
        access = PostgresUserManagement.workspaceAccess(jdbc);
        projection = PostgresUserManagement.memberships(jdbc);
    }

    private record Member(UUID userId, UUID subject) {}

    private record Agency(UUID workspaceId, Member founder) {}

    /**
     * Through {@link WorkspaceService#create}, not a seeded row.
     *
     * <p>These helpers used to insert into {@code um_workspace} and {@code um_membership} directly,
     * which was harmless while membership was a table and is not now. The aggregate decides, so a
     * workspace with no {@code WorkspaceCreated} on its stream rehydrates with a null id, and every
     * event it emits afterwards names the wrong agency — silently, because a null uuid is a
     * perfectly valid uuid column. The founding ADMIN arrives as a member for the same reason.
     */
    private static Agency agency(String name) {
        var subject = UUID.randomUUID();
        var founderId = users.register(subject, TODAY);
        return new Agency(workspaces.create(name, founderId, TODAY), new Member(founderId, subject));
    }

    /** Seeds an extra member the way acceptance does: event first, projection second. */
    private static Member member(UUID workspaceId, Role role) {
        var subject = UUID.randomUUID();
        var userId = users.register(subject, TODAY);
        var stream = store.load(workspaceId, "Workspace");
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new MemberJoined(workspaceId, userId, role, TODAY)), List.of());
        projection.join(workspaceId, userId, role, TODAY);
        return new Member(userId, subject);
    }

    @Test
    void roleChangeIsVisibleThroughTheAccessReadModel() {
        var agency = agency("Agencja Role");
        var manager = member(agency.workspaceId(), Role.MANAGER);

        memberships.changeRole(agency.workspaceId(), manager.userId(), Role.ADMIN, LATER);

        assertThat(access.roleIn(manager.subject(), agency.workspaceId())).contains(Role.ADMIN);
    }

    @Test
    void roleChangeIsRecordedAsAnEventOnTheWorkspaceStream() {
        var agency = agency("Agencja Audyt");
        var manager = member(agency.workspaceId(), Role.MANAGER);

        memberships.changeRole(agency.workspaceId(), manager.userId(), Role.ADMIN, LATER);

        var types = jdbc.queryForList(
            "select event_type from events where stream_id = ? order by version", String.class,
            agency.workspaceId());
        // The founder's MemberJoined leads, because creating an agency now records who founded it.
        assertThat(types).containsExactly(
            "WorkspaceCreated", "MemberJoined", "MemberJoined", "MemberRoleChanged");
    }

    @Test
    void removedMemberLosesAccess() {
        var agency = agency("Agencja Remove");
        var leaving = member(agency.workspaceId(), Role.MANAGER);

        memberships.remove(agency.workspaceId(), leaving.userId(), LATER);

        assertThat(access.canAccess(leaving.subject(), agency.workspaceId())).isFalse();
    }

    @Test
    void theLastAdminCannotBeRemoved() {
        var agency = agency("Agencja Ostatni");

        assertThatThrownBy(() -> memberships.remove(agency.workspaceId(), agency.founder().userId(), LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least one admin");
    }

    @Test
    void theLastAdminCannotBeDemoted() {
        var agency = agency("Agencja Demote");

        assertThatThrownBy(() ->
            memberships.changeRole(agency.workspaceId(), agency.founder().userId(), Role.MANAGER, LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least one admin");
    }

    /** The rule bites on the last ADMIN, not on the last member: a second admin frees the first. */
    @Test
    void anAdminMayBeRemovedOnceThereIsAnother() {
        var agency = agency("Agencja Dwoje");
        member(agency.workspaceId(), Role.ADMIN);

        memberships.remove(agency.workspaceId(), agency.founder().userId(), LATER);

        assertThat(access.canAccess(agency.founder().subject(), agency.workspaceId())).isFalse();
    }

    @Test
    void aNonMemberCannotBeChanged() {
        var agency = agency("Agencja Obca");
        var stranger = agency("Agencja Inna").founder();

        assertThatThrownBy(() ->
            memberships.changeRole(agency.workspaceId(), stranger.userId(), Role.MANAGER, LATER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a member");
    }

    @Test
    void accessNeverLeaksAcrossWorkspaces() {
        var mine = agency("Agencja Moja");
        var theirs = agency("Agencja Obca 2");
        var me = member(mine.workspaceId(), Role.MANAGER);
        member(theirs.workspaceId(), Role.MANAGER);

        assertThat(access.canAccess(me.subject(), theirs.workspaceId())).isFalse();
        assertThat(access.forSubject(me.subject()))
            .extracting(WorkspaceAccess.Membership::workspaceId)
            .containsExactly(mine.workspaceId());
    }

    /**
     * The screens must name the agency rather than print its UUID, so the name has to survive the
     * read. The second assertion is the one that matters: the name arrives via a join, and a join
     * that matches nothing drops the whole membership — which would read as "this user belongs to
     * no agency" and be refused, not as a missing label.
     */
    @Test
    void aMembershipCarriesTheAgencysNameAndNotOnlyItsId() {
        var agency = agency("Agencja Nazwana");
        var me = member(agency.workspaceId(), Role.MANAGER);

        assertThat(access.forSubject(me.subject()))
            .singleElement()
            .satisfies(m -> {
                assertThat(m.name()).isEqualTo("Agencja Nazwana");
                assertThat(m.workspaceId()).isEqualTo(agency.workspaceId());
            });
    }

    @Test
    void anUnknownSubjectHasNoAccessAtAll() {
        assertThat(access.forSubject(UUID.randomUUID())).isEmpty();
    }
}
