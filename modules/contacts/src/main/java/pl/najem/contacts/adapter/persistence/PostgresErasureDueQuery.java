package pl.najem.contacts.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.contacts.application.ErasureDueQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class PostgresErasureDueQuery implements ErasureDueQuery {

    private final JdbcTemplate jdbc;

    public PostgresErasureDueQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The {@code h.workspace_id = p.workspace_id} join predicate is redundant TODAY, and is stated
     * anyway. {@code ContactDirectory.requireIn} refuses to raise a hold on another workspace's
     * contact, so no row can exist for which it changes the answer — a mutation removing it will
     * survive, and that is expected rather than a gap in the tests. It is here because this query
     * and the hold register are two statements of one rule, and without it they said different
     * things.
     */
    @Override
    public List<UUID> dueForErasure(UUID workspaceId, LocalDate asOf) {
        return jdbc.queryForList("""
            select p.contact_id from contacts_person p
            where p.workspace_id = ? and p.retain_until is not null and p.retain_until <= ?
              and not exists (select 1 from contacts_retention_hold h
                              where h.contact_id = p.contact_id
                                and h.workspace_id = p.workspace_id
                                and h.released_on is null)
            order by p.retain_until
            """, UUID.class, workspaceId, asOf);
    }
}
