package pl.najem.contacts.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.contacts.application.RetentionHoldRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class PostgresRetentionHoldRepository implements RetentionHoldRepository {

    private final JdbcTemplate jdbc;

    public PostgresRetentionHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void set(UUID workspaceId, UUID contactId, String reason, String sourceRef,
                    LocalDate setOn) {
        jdbc.update("""
            insert into contacts_retention_hold(contact_id, workspace_id, reason, source_ref, set_on)
            values (?,?,?,?,?)
            on conflict (contact_id, reason, source_ref)
              do update set set_on = excluded.set_on, released_on = null
            """, contactId, workspaceId, reason, sourceRef, setOn);
    }

    @Override
    public void release(UUID workspaceId, UUID contactId, String reason, String sourceRef,
                        LocalDate releasedOn) {
        jdbc.update("""
            update contacts_retention_hold set released_on = ?
            where workspace_id = ? and contact_id = ? and reason = ? and source_ref = ?
            """, releasedOn, workspaceId, contactId, reason, sourceRef);
    }

    @Override
    public List<String> activeReasons(UUID workspaceId, UUID contactId) {
        return jdbc.queryForList("""
            select distinct reason from contacts_retention_hold
            where workspace_id = ? and contact_id = ? and released_on is null order by reason
            """, String.class, workspaceId, contactId);
    }
}
