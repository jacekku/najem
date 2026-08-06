package pl.najem.contacts.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The record of a person. {@code contacts_person} is the only table in the system holding personal
 * data, and it is a record rather than a projection: nothing rebuilds it, and the event streams
 * deliberately do not carry the names it holds. Right-to-be-forgotten is the deletion of a row here.
 *
 * <p>{@code Repository} and not {@code Projection} because contacts is not event-sourced. Every
 * {@code store.load} in this module is used for {@code version()} on append and nothing else, there
 * is no aggregate class in the domain, and no state derives from the stream (rule 1: the same
 * premise was checked and found false in accounting).
 *
 * <p>The erasure log lives here too, because it is what remains of a person after the row is gone —
 * a tombstone answering "was this contact yours?" once nothing else can. A caller that needed it
 * without needing this port has not appeared.
 */
public interface ContactRepository {

    Optional<ContactDetails> find(UUID workspaceId, UUID contactId);

    /**
     * Matches a name fragment across given name, surname, and the two joined by a space.
     *
     * <p>{@code term} arrives raw and the implementation is responsible for making it a term rather
     * than a pattern — a search for {@code %} finds people whose name contains a percent sign, not
     * every person in the workspace. That escaping is dialect-specific, which is why it is on this
     * side of the port and why the in-memory double has to reproduce the behaviour rather than the
     * SQL (rule 14).
     *
     * <p>{@code limit} is passed in rather than assumed, because how much of a directory a search
     * box may return is policy and does not belong to the store.
     */
    List<ContactMatch> search(UUID workspaceId, String term, int limit);

    /** Candidates for "do we already know this person?" — the manager judges, no uniqueness enforced. */
    List<UUID> findByEmail(UUID workspaceId, String email);

    void insert(UUID contactId, NewContact contact);

    void updateDetails(UUID workspaceId, UUID contactId, ContactDetails details);

    /**
     * Deletes the personal data and reports whether there was any.
     *
     * <p>The count is the mechanism, not a convenience: {@code ContactService.erase} distinguishes a
     * first erasure from an idempotent repeat by whether a row went, and a double that returned a
     * bare {@code void} or an unconditional {@code true} would make that branch untestable.
     */
    int delete(UUID workspaceId, UUID contactId);

    /**
     * Known to this workspace, or erased by it — the two halves of the erasure gate.
     *
     * <p>Both halves are workspace-scoped, so unknown, foreign, and foreign-already-erased are one
     * answer. That is deliberate and is what keeps the gate from being an oracle.
     */
    boolean isKnownOrErased(UUID workspaceId, UUID contactId);

    void logErasure(UUID workspaceId, UUID contactId, LocalDate erasedOn);
}
