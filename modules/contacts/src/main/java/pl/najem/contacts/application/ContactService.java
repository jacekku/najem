package pl.najem.contacts.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contacts.domain.ContactDetailsCorrected;
import pl.najem.contacts.domain.ContactErased;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.eventstore.EventStore;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ContactService {

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final RetentionService retention;
    private final ContactDirectory directory;

    public ContactService(EventStore store, JdbcTemplate jdbc, RetentionService retention,
                          ContactDirectory directory) {
        this.store = store;
        this.jdbc = jdbc;
        this.retention = retention;
        this.directory = directory;
    }

    public UUID register(NewContact contact) {
        UUID contactId = UUID.randomUUID();
        var stream = store.load(contactId, "Contact");
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

    public void correctDetails(UUID workspaceId, UUID contactId, ContactDetails details, LocalDate correctedOn) {
        directory.requireIn(workspaceId, contactId);
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactDetailsCorrected(workspaceId, contactId, correctedOn)), List.of());
        jdbc.update("""
            update contacts_person set given_name = ?, surname = ?, email = ?, phone = ?
            where workspace_id = ? and contact_id = ?
            """,
            details.givenName(), details.surname(), details.email(), details.phone(), workspaceId, contactId);
    }

    /**
     * Right-to-be-forgotten: delete the personal data, keep the event stream.
     * Refuses while any retention hold is unreleased (tax / civil prescription).
     */
    public void erase(UUID workspaceId, UUID contactId, LocalDate erasedOn) {
        var holds = retention.activeHolds(workspaceId, contactId);
        if (!holds.isEmpty()) {
            throw new RetentionHoldActiveException(contactId, holds);
        }
        int erased = jdbc.update("delete from contacts_person where workspace_id = ? and contact_id = ?",
            workspaceId, contactId);
        if (erased == 0) {
            // Unknown here, or another workspace's contact: erase nothing, claim nothing.
            return;
        }
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactErased(workspaceId, contactId, erasedOn)), List.of());
        jdbc.update("delete from contacts_interest where workspace_id = ? and contact_id = ?",
            workspaceId, contactId);
        jdbc.update("insert into contacts_erasure_log(contact_id, workspace_id, erased_on) values (?,?,?)",
            contactId, workspaceId, erasedOn);
    }
}
