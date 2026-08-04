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

    /** Candidates for "do we already know this person?" — the manager judges, no uniqueness enforced. */
    public List<UUID> findByEmail(UUID workspaceId, String email) {
        return jdbc.queryForList("""
            select contact_id from contacts_person
            where workspace_id = ? and email = ? order by contact_id
            """, UUID.class, workspaceId, email);
    }
}
