package pl.najem.contacts.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.eventstore.EventStore;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ContactService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public ContactService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID register(NewContact contact) {
        UUID contactId = UUID.randomUUID();
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactRegistered(contact.workspaceId(), contactId, contact.lawfulBasis(),
                contact.infoClauseServedAt(), contact.retainUntil())), List.of());
        jdbc.update("""
            insert into contacts_person(contact_id, workspace_id, given_name, surname, email, phone,
                                        lawful_basis, info_clause_served_at, retain_until)
            values (?,?,?,?,?,?,?,?,?)
            """,
            contactId, contact.workspaceId(), contact.details().givenName(), contact.details().surname(),
            contact.details().email(), contact.details().phone(),
            contact.lawfulBasis(), contact.infoClauseServedAt(), contact.retainUntil());
        return contactId;
    }
}
