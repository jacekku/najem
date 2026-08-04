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
    private final ContactDirectory directory;

    public RetentionService(EventStore store, JdbcTemplate jdbc, ContactDirectory directory) {
        this.store = store;
        this.jdbc = jdbc;
        this.directory = directory;
    }

    /** A hold set by a manager by hand: it answers to nobody but the manager. */
    public static final String MANUAL = "manual";

    public void setHold(UUID workspaceId, UUID contactId, String reason, LocalDate setOn) {
        setHold(workspaceId, contactId, reason, MANUAL, setOn);
    }

    /**
     * Raises {@code reason} on behalf of {@code sourceRef}. Re-asserting an existing
     * (contact, reason, source) is deliberately idempotent — a trigger re-fanning-out over a
     * changed roster will send the same hold repeatedly, and that is normal traffic, not an error.
     */
    public void setHold(UUID workspaceId, UUID contactId, String reason, String sourceRef, LocalDate setOn) {
        directory.requireIn(workspaceId, contactId);
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new RetentionHoldSet(workspaceId, contactId, reason, sourceRef, setOn)), List.of());
        jdbc.update("""
            insert into contacts_retention_hold(contact_id, workspace_id, reason, source_ref, set_on)
            values (?,?,?,?,?)
            on conflict (contact_id, reason, source_ref)
              do update set set_on = excluded.set_on, released_on = null
            """, contactId, workspaceId, reason, sourceRef, setOn);
    }

    public void releaseHold(UUID workspaceId, UUID contactId, String reason, LocalDate releasedOn) {
        releaseHold(workspaceId, contactId, reason, MANUAL, releasedOn);
    }

    /** Releases only {@code sourceRef}'s hold. Another source's hold on the same reason survives. */
    public void releaseHold(UUID workspaceId, UUID contactId, String reason, String sourceRef, LocalDate releasedOn) {
        directory.requireIn(workspaceId, contactId);
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new RetentionHoldReleased(workspaceId, contactId, reason, sourceRef, releasedOn)), List.of());
        jdbc.update("""
            update contacts_retention_hold set released_on = ?
            where workspace_id = ? and contact_id = ? and reason = ? and source_ref = ?
            """, releasedOn, workspaceId, contactId, reason, sourceRef);
    }

    /** The reasons erasure is currently blocked for — distinct, because a caller needs causes, not rows. */
    public List<String> activeHolds(UUID workspaceId, UUID contactId) {
        return jdbc.queryForList("""
            select distinct reason from contacts_retention_hold
            where workspace_id = ? and contact_id = ? and released_on is null order by reason
            """, String.class, workspaceId, contactId);
    }

    public boolean hasActiveHold(UUID workspaceId, UUID contactId) {
        return !activeHolds(workspaceId, contactId).isEmpty();
    }

    /**
     * Reports only — erasure stays a deliberate act while hotspot #15 (retention duration) is open.
     * <p>
     * The {@code h.workspace_id = p.workspace_id} join predicate is redundant TODAY, and is stated
     * anyway. {@link ContactDirectory#requireIn} now refuses to raise a hold on another workspace's
     * contact, so no row can exist for which it changes the answer — a mutation removing it will
     * survive, and that is expected rather than a gap in the tests.
     * <p>
     * It is here because this query and {@link #hasActiveHold} are two statements of one rule, and
     * without it they said different things: a hold raised by anybody removed the contact from this
     * report while leaving the erasure gate open. That disagreement failed toward <em>keeping</em>
     * personal data past its retention date and telling the operator there was nothing to erase.
     * Making the two agree is worth a line the guard already makes unreachable.
     */
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
