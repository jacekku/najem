package pl.najem.contacts.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contacts.domain.RetentionHoldReleased;
import pl.najem.contacts.domain.RetentionHoldSet;
import pl.najem.eventstore.EventStore;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RetentionService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public RetentionService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public void setHold(UUID workspaceId, UUID contactId, String reason, LocalDate setOn) {
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new RetentionHoldSet(workspaceId, contactId, reason, setOn)), List.of());
        jdbc.update("""
            insert into contacts_retention_hold(contact_id, workspace_id, reason, set_on) values (?,?,?,?)
            on conflict (contact_id, reason) do update set set_on = excluded.set_on, released_on = null
            """, contactId, workspaceId, reason, setOn);
    }

    public void releaseHold(UUID workspaceId, UUID contactId, String reason, LocalDate releasedOn) {
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new RetentionHoldReleased(workspaceId, contactId, reason, releasedOn)), List.of());
        jdbc.update("""
            update contacts_retention_hold set released_on = ?
            where workspace_id = ? and contact_id = ? and reason = ?
            """, releasedOn, workspaceId, contactId, reason);
    }

    public List<String> activeHolds(UUID workspaceId, UUID contactId) {
        return jdbc.queryForList("""
            select reason from contacts_retention_hold
            where workspace_id = ? and contact_id = ? and released_on is null order by reason
            """, String.class, workspaceId, contactId);
    }

    public boolean hasActiveHold(UUID workspaceId, UUID contactId) {
        return !activeHolds(workspaceId, contactId).isEmpty();
    }

    /** Reports only — erasure stays a deliberate act while hotspot #15 (retention duration) is open. */
    public List<UUID> dueForErasure(UUID workspaceId, LocalDate asOf) {
        return jdbc.queryForList("""
            select p.contact_id from contacts_person p
            where p.workspace_id = ? and p.retain_until is not null and p.retain_until <= ?
              and not exists (select 1 from contacts_retention_hold h
                              where h.contact_id = p.contact_id and h.released_on is null)
            order by p.retain_until
            """, UUID.class, workspaceId, asOf);
    }
}
