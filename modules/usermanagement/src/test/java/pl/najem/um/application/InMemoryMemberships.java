package pl.najem.um.application;

import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link MembershipProjection}. Stands in for {@code um_membership}.
 *
 * <p>Composed from the user and workspace fakes rather than holding names and subjects of its own,
 * because the two list queries are <b>joins</b> — {@code ofSubject} goes through {@code um_user},
 * both go through {@code um_workspace} for the name. A fake that stored the name alongside the
 * membership would return a row where the real query returns nothing, and the case that matters is
 * exactly the one it would get wrong: a membership whose workspace or user row is missing.
 */
public class InMemoryMemberships implements MembershipProjection {

    private record Row(UUID workspaceId, UUID userId, Role role, LocalDate joinedOn) {}

    private final List<Row> rows = new ArrayList<>();
    private final InMemoryUsers users;
    private final InMemoryWorkspaces workspaces;

    public InMemoryMemberships(InMemoryUsers users, InMemoryWorkspaces workspaces) {
        this.users = users;
        this.workspaces = workspaces;
    }

    /** Upsert on (workspace, user), as the statement is. */
    @Override
    public void join(UUID workspaceId, UUID userId, Role role, LocalDate on) {
        var existing = row(workspaceId, userId);
        if (existing.isPresent()) {
            rows.set(rows.indexOf(existing.get()),
                new Row(workspaceId, userId, role, existing.get().joinedOn()));
            return;
        }
        rows.add(new Row(workspaceId, userId, role, on));
    }

    @Override
    public void changeRole(UUID workspaceId, UUID userId, Role role) {
        row(workspaceId, userId).ifPresent(r ->
            rows.set(rows.indexOf(r), new Row(workspaceId, userId, role, r.joinedOn())));
    }

    @Override
    public void remove(UUID workspaceId, UUID userId) {
        row(workspaceId, userId).ifPresent(rows::remove);
    }

    @Override
    public Optional<Role> roleOf(UUID workspaceId, UUID userId) {
        return row(workspaceId, userId).map(Row::role);
    }

    @Override
    public List<Membership> ofUser(UUID userId) {
        return join(rows.stream().filter(r -> r.userId().equals(userId)).toList());
    }

    /** No join: the roles a workspace grants exist in this table alone. */
    @Override
    public Map<UUID, Role> rolesIn(UUID workspaceId) {
        Map<UUID, Role> roles = new LinkedHashMap<>();
        rows.stream().filter(r -> r.workspaceId().equals(workspaceId))
            .forEach(r -> roles.put(r.userId(), r.role()));
        return roles;
    }

    @Override
    public List<Membership> ofSubject(UUID keycloakSubject) {
        return join(rows.stream()
            .filter(r -> users.subjectOf(r.userId()).filter(keycloakSubject::equals).isPresent())
            .toList());
    }

    /** The join to um_workspace: a membership whose workspace row is gone yields no row at all. */
    private List<Membership> join(List<Row> matched) {
        return matched.stream()
            .sorted(Comparator.comparing(Row::joinedOn).thenComparing(Row::workspaceId))
            .filter(r -> workspaces.names.containsKey(r.workspaceId()))
            .map(r -> new Membership(r.workspaceId(), workspaces.names.get(r.workspaceId()), r.role()))
            .toList();
    }

    private Optional<Row> row(UUID workspaceId, UUID userId) {
        return rows.stream()
            .filter(r -> r.workspaceId().equals(workspaceId) && r.userId().equals(userId))
            .findFirst();
    }
}
