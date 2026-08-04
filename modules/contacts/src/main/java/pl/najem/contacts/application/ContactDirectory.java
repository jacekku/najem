package pl.najem.contacts.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ContactDirectory {

    private final JdbcTemplate jdbc;

    public ContactDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ContactDetails> find(UUID workspaceId, UUID contactId) {
        return jdbc.query("""
                select given_name, surname, email, phone from contacts_person
                where workspace_id = ? and contact_id = ?
                """,
                (rs, i) -> new ContactDetails(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                workspaceId, contactId)
            .stream().findFirst();
    }

    /**
     * The ownership gate every command goes through before it touches a contact's stream.
     * <p>
     * It lives here because this class is already the authority on "does this workspace know this
     * contact", and a second place answering that question would eventually answer it differently —
     * which is the defect this method was written to close, where {@code hasActiveHold} filtered by
     * workspace and {@code dueForErasure} did not.
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
        Integer known = jdbc.queryForObject("""
            select count(*) from (
                select contact_id from contacts_person where workspace_id = ? and contact_id = ?
                union all
                select contact_id from contacts_erasure_log where workspace_id = ? and contact_id = ?
            ) mine
            """, Integer.class, workspaceId, contactId, workspaceId, contactId);
        if (known == null || known == 0) {
            throw new NoSuchContactException(contactId);
        }
    }

    /**
     * A hit in the people half of search. {@code contactId} is what every other contacts endpoint
     * takes, so a hit is navigable rather than merely informative.
     */
    public record Match(UUID contactId, String givenName, String surname, String email) {
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
     * Matches a name fragment across given name and surname, workspace-scoped like every other read
     * here: a blank term returns nothing rather than the whole directory.
     */
    public List<Match> search(UUID workspaceId, String term) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        var pattern = "%" + escapeLike(term.strip()) + "%";
        return jdbc.query("""
            select contact_id, given_name, surname, email from contacts_person
            where workspace_id = ?
              and (given_name ilike ? escape '\\' or surname ilike ? escape '\\'
                   or (given_name || ' ' || surname) ilike ? escape '\\')
            order by surname, given_name
            limit %d
            """.formatted(SEARCH_LIMIT),
            (rs, i) -> new Match(UUID.fromString(rs.getString(1)),
                rs.getString(2), rs.getString(3), rs.getString(4)),
            workspaceId, pattern, pattern, pattern);
    }

    /** A term containing {@code %} is a term, not a wildcard that returns the whole directory. */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Candidates for "do we already know this person?" — the manager judges, no uniqueness enforced. */
    public List<UUID> findByEmail(UUID workspaceId, String email) {
        return jdbc.queryForList("""
            select contact_id from contacts_person
            where workspace_id = ? and email = ? order by contact_id
            """, UUID.class, workspaceId, email);
    }
}
