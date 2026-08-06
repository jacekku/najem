package pl.najem.contacts.application;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ContactDirectory {

    private final ContactRepository contacts;

    public ContactDirectory(ContactRepository contacts) {
        this.contacts = contacts;
    }

    public Optional<ContactDetails> find(UUID workspaceId, UUID contactId) {
        return contacts.find(workspaceId, contactId);
    }

    /**
     * The ownership gate every command goes through before it touches a contact's stream.
     * <p>
     * It lives here because this class is already the authority on "does this workspace know this
     * contact", and a second place answering that question would eventually answer it differently —
     * which is the defect this method was written to close, where {@code hasActiveHold} filtered by
     * workspace and {@code dueForErasure} did not.
     * <p>
     * It reads the record to answer, which is what makes it different from the guard
     * property-management retired. There, a projection was asked who owned a subject while the
     * service rebuilt the aggregate holding the same fact one line later — one question with two
     * answers. Here {@code contacts_person} is the only place the answer exists.
     * <p>
     * Callers must invoke it BEFORE appending to the event stream, not merely before the SQL write.
     * Every write in this module was already scoped by {@code workspace_id} and every one of them
     * was still reachable, because the append ran first and a scoped update that matches no row
     * fails silently by design.
     */
    public void requireIn(UUID workspaceId, UUID contactId) {
        if (find(workspaceId, contactId).isEmpty()) {
            throw new NoSuchContactException(contactId);
        }
    }

    /**
     * The gate for erasure specifically: known-and-yours, <em>or</em> already-erased-by-you.
     * <p>
     * {@link #requireIn} cannot serve this one endpoint, because erasure deletes the
     * {@code contacts_person} row — so a legitimate second erasure of your own contact would 404 on
     * a contact you had just successfully erased, and idempotency would be gone. The erasure log is
     * the record that it was yours, and it survives.
     * <p>
     * Both halves are workspace-scoped, so the status is identical for unknown, foreign, and
     * foreign-already-erased. That undifferentiated 404 is what keeps this from being an oracle —
     * @najem-reviewer's point at najem-build seq 266, and the reason the argument I made for
     * silence does not hold: the other four commands already merge unknown with foreign, so a 404
     * here discloses nothing they do not.
     */
    public void requireErasable(UUID workspaceId, UUID contactId) {
        if (!contacts.isKnownOrErased(workspaceId, contactId)) {
            throw new NoSuchContactException(contactId);
        }
    }

    /** A search box is a browse aid, not an export. */
    private static final int SEARCH_LIMIT = 50;

    /**
     * The people half of {@code search}, served here rather than from Reporting.
     * <p>
     * <b>It has to live here, and that is a design consequence rather than a convenience.</b>
     * Reporting cannot search people: the PII lookaside means events carry identifiers only, so
     * Reporting has never seen a name to project. If it had one, that projection would be a second
     * copy of personal data surviving the {@code contacts_person} row deletion that <em>is</em>
     * erasure — so the erasure this module implements would stop being complete the moment search
     * was built anywhere else. Reading the live table instead means an erased person leaves search
     * at the instant they are erased, with no projector to catch up.
     * <p>
     * The blank term and the limit stay here rather than moving behind the port: how much of a
     * directory a search box may return, and whether an empty box means everybody or nobody, are
     * decisions this module owns and must not vary with the store (rule 10). Escaping the term so
     * that {@code %} is a character rather than a wildcard is dialect-specific and did move.
     */
    public List<ContactMatch> search(UUID workspaceId, String term) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        return contacts.search(workspaceId, term.strip(), SEARCH_LIMIT);
    }

    /** Candidates for "do we already know this person?" — the manager judges, no uniqueness enforced. */
    public List<UUID> findByEmail(UUID workspaceId, String email) {
        return contacts.findByEmail(workspaceId, email);
    }
}
