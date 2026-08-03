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

    /** Candidates for "do we already know this person?" — the manager judges, no uniqueness enforced. */
    public List<UUID> findByEmail(UUID workspaceId, String email) {
        return jdbc.queryForList("""
            select contact_id from contacts_person
            where workspace_id = ? and email = ? order by contact_id
            """, UUID.class, workspaceId, email);
    }
}
