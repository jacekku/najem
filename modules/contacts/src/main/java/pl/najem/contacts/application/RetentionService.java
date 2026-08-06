package pl.najem.contacts.application;

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
    private final RetentionHoldRepository holds;
    private final ErasureDueQuery erasureDue;
    private final ContactDirectory directory;

    public RetentionService(EventStore store, RetentionHoldRepository holds,
                            ErasureDueQuery erasureDue, ContactDirectory directory) {
        this.store = store;
        this.holds = holds;
        this.erasureDue = erasureDue;
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
        holds.set(workspaceId, contactId, reason, sourceRef, setOn);
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
        holds.release(workspaceId, contactId, reason, sourceRef, releasedOn);
    }

    /** The reasons erasure is currently blocked for — distinct, because a caller needs causes, not rows. */
    public List<String> activeHolds(UUID workspaceId, UUID contactId) {
        return holds.activeReasons(workspaceId, contactId);
    }

    public boolean hasActiveHold(UUID workspaceId, UUID contactId) {
        return !activeHolds(workspaceId, contactId).isEmpty();
    }

    /**
     * Reports only — erasure stays a deliberate act while hotspot #15 (retention duration) is open.
     *
     * <p>This and {@link #hasActiveHold} are two statements of one rule and must keep agreeing;
     * {@link ErasureDueQuery} records what happened when they did not.
     */
    public List<UUID> dueForErasure(UUID workspaceId, LocalDate asOf) {
        return erasureDue.dueForErasure(workspaceId, asOf);
    }
}
