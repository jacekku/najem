package pl.najem.contacts.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactMatch;
import pl.najem.contacts.application.ContactRepository;
import pl.najem.contacts.application.NewContact;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresContactRepository implements ContactRepository {

    private final JdbcTemplate jdbc;

    public PostgresContactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ContactDetails> find(UUID workspaceId, UUID contactId) {
        return jdbc.query("""
                select given_name, surname, email, phone from contacts_person
                where workspace_id = ? and contact_id = ?
                """,
                (rs, i) -> new ContactDetails(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                workspaceId, contactId)
            .stream().findFirst();
    }

    @Override
    public List<ContactMatch> search(UUID workspaceId, String term, int limit) {
        var pattern = "%" + escapeLike(term) + "%";
        return jdbc.query("""
            select contact_id, given_name, surname, email from contacts_person
            where workspace_id = ?
              and (given_name ilike ? escape '\\' or surname ilike ? escape '\\'
                   or (given_name || ' ' || surname) ilike ? escape '\\')
            order by surname, given_name
            limit %d
            """.formatted(limit),
            (rs, i) -> new ContactMatch(UUID.fromString(rs.getString(1)),
                rs.getString(2), rs.getString(3), rs.getString(4)),
            workspaceId, pattern, pattern, pattern);
    }

    /** A term containing {@code %} is a term, not a wildcard that returns the whole directory. */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public List<UUID> findByEmail(UUID workspaceId, String email) {
        return jdbc.queryForList("""
            select contact_id from contacts_person
            where workspace_id = ? and email = ? order by contact_id
            """, UUID.class, workspaceId, email);
    }

    @Override
    public void insert(UUID contactId, NewContact contact) {
        jdbc.update("""
            insert into contacts_person(contact_id, workspace_id, given_name, surname, email, phone,
                                        lawful_basis, info_clause_served_at, retain_until)
            values (?,?,?,?,?,?,?,?,?)
            """,
            contactId, contact.workspaceId(), contact.details().givenName(), contact.details().surname(),
            contact.details().email(), contact.details().phone(),
            contact.lawfulBasis(), contact.infoClauseServedAt(), contact.retainUntil());
    }

    @Override
    public void updateDetails(UUID workspaceId, UUID contactId, ContactDetails details) {
        jdbc.update("""
            update contacts_person set given_name = ?, surname = ?, email = ?, phone = ?
            where workspace_id = ? and contact_id = ?
            """,
            details.givenName(), details.surname(), details.email(), details.phone(), workspaceId, contactId);
    }

    @Override
    public int delete(UUID workspaceId, UUID contactId) {
        return jdbc.update("delete from contacts_person where workspace_id = ? and contact_id = ?",
            workspaceId, contactId);
    }

    @Override
    public boolean isKnownOrErased(UUID workspaceId, UUID contactId) {
        Integer known = jdbc.queryForObject("""
            select count(*) from (
                select contact_id from contacts_person where workspace_id = ? and contact_id = ?
                union all
                select contact_id from contacts_erasure_log where workspace_id = ? and contact_id = ?
            ) mine
            """, Integer.class, workspaceId, contactId, workspaceId, contactId);
        return known != null && known > 0;
    }

    @Override
    public void logErasure(UUID workspaceId, UUID contactId, LocalDate erasedOn) {
        jdbc.update("insert into contacts_erasure_log(contact_id, workspace_id, erased_on) values (?,?,?)",
            contactId, workspaceId, erasedOn);
    }
}
