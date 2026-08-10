package pl.najem.contacts.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contacts.domain.ContactDetailsCorrected;
import pl.najem.contacts.domain.ContactErased;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.contacts.domain.LawfulBasisChanged;
import pl.najem.eventstore.EventStore;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ContactService {

    /** The basis a tenant or guarantor is held under. */
    private static final String CONTRACT = "contract";

    private final EventStore store;
    private final ContactRepository contacts;
    private final InterestRepository interests;
    private final RetentionService retention;
    private final ContactDirectory directory;

    public ContactService(EventStore store, ContactRepository contacts, InterestRepository interests,
                          RetentionService retention, ContactDirectory directory) {
        this.store = store;
        this.contacts = contacts;
        this.interests = interests;
        this.retention = retention;
        this.directory = directory;
    }

    public UUID register(NewContact contact) {
        UUID contactId = UUID.randomUUID();
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactRegistered(contact.workspaceId(), contactId, contact.lawfulBasis(),
                contact.infoClauseServedAt(), contact.retainUntil())), List.of());
        contacts.insert(contactId, contact);
        return contactId;
    }

    /**
     * Somebody who phoned about a unit.
     *
     * <p>The basis is fixed here rather than asked for at the edge, per refactoring rule 10: what
     * basis a lead is registered under is this module's decision, and a constant in a controller
     * would be answered again — possibly differently — by the next screen that registers one.
     * {@code legitimate-interest} is what {@code ContactLifecycleTest} has always used for this kind
     * of person; a tenant is {@code contract}, and the two must not be merged.
     *
     * <p>{@code retainUntil} is deliberately null. How long an agency keeps a lead it never let to
     * is a retention policy nobody has decided, and inventing one here would put a date in the
     * erasure queue that no rule stands behind.
     *
     * <p>{@code today} is a parameter because this module reads no clock — {@code WallClockTest}
     * asserts it — and because the served date is the caller's fact, not the store's.
     */
    public UUID registerLead(UUID workspaceId, ContactDetails details,
                             boolean infoClauseServed, LocalDate today) {
        return register(new NewContact(workspaceId, details, "legitimate-interest",
            infoClauseServed ? today : null, null));
    }

    /**
     * A guarantor, or a co-tenant the agency did not already know.
     *
     * <p>{@code contract} rather than {@code legitimate-interest}: nobody phoned about a unit, and the
     * only reason the agency holds these details is the agreement being signed. Kept separate from
     * {@link #registerLead} for the reason that method's javadoc gives — the two bases are not
     * interchangeable and merging them would decide the retention question by accident.
     *
     * <p><b>The basis is asserted ahead of the fact.</b> This method runs in phase 1 of the reserve
     * screen, before {@code ReserveTenancy} is even built — so a name typed here is labelled
     * {@code contract} while no contract yet exists, and stays that way if the manager abandons the
     * form. That is the same shape of orphan {@link #registerLead} leaves under
     * {@code legitimate-interest}, which is true of somebody who phoned; this one is not true of
     * somebody who has not signed. It is accepted anyway, for the same reason the lead-orphan is:
     * the alternative is tracking which contacts are "provisional" through a form that already spans
     * two screens and a redirect, which is exactly the kind of implicit state this module's event
     * stream exists to avoid, in exchange for correctness on a party who signs a moment later — the
     * ordinary case, since a guarantor has no reason to be added except to be signed. {@code retainUntil}
     * is null here as it is everywhere else in this class, so nothing sweeps an abandoned one; closing
     * that gap is retention policy nobody has decided, not a defect of this method.
     */
    public UUID registerParty(UUID workspaceId, ContactDetails details,
                              boolean infoClauseServed, LocalDate today) {
        return register(new NewContact(workspaceId, details, CONTRACT,
            infoClauseServed ? today : null, null));
    }

    /**
     * A lead who signed. Idempotent by reading first: appending a change from {@code contract} to
     * {@code contract} would put a decision nobody made into a stream that exists to prove what was
     * decided, and a guarantor registered moments earlier is already there.
     *
     * <p>{@code retainUntil} is still not set. How long an agency keeps a former tenant is a
     * retention policy nobody has decided, and this is not where it gets invented.
     */
    public void becameContractParty(UUID workspaceId, UUID contactId, LocalDate today) {
        String basis = contacts.lawfulBasisOf(workspaceId, contactId)
            .orElseThrow(() -> new NoSuchContactException(contactId));
        if (CONTRACT.equals(basis)) {
            return;
        }
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new LawfulBasisChanged(workspaceId, contactId, CONTRACT, today)), List.of());
        contacts.updateLawfulBasis(workspaceId, contactId, CONTRACT);
    }

    public void correctDetails(UUID workspaceId, UUID contactId, ContactDetails details, LocalDate correctedOn) {
        directory.requireIn(workspaceId, contactId);
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactDetailsCorrected(workspaceId, contactId, correctedOn)), List.of());
        contacts.updateDetails(workspaceId, contactId, details);
    }

    /**
     * Right-to-be-forgotten: delete the personal data, keep the event stream.
     * Refuses while any retention hold is unreleased (tax / civil prescription).
     * <p>
     * A contact that is not yours is a 404, not a silent 204. I argued the opposite when the gate
     * landed — that deleting nothing is the correct outcome of an erase command, so there was
     * nothing to report — and @najem-reviewer contested it at najem-build seq 266 on grounds I
     * could not answer: <b>a 204 means erased</b>, so a manager who mistypes an id, or whose client
     * is pointed at the wrong workspace, is told the person's data is gone while it is still there.
     * Under a right-to-be-forgotten request the controller has to be able to demonstrate the
     * erasure, and "the database correctly deleted nothing" does not discharge that.
     * <p>
     * The oracle objection I raised does not apply, because {@link ContactDirectory#requireIn}
     * already answers unknown and foreign identically for the other four commands.
     */
    public void erase(UUID workspaceId, UUID contactId, LocalDate erasedOn) {
        directory.requireErasable(workspaceId, contactId);
        var holds = retention.activeHolds(workspaceId, contactId);
        if (!holds.isEmpty()) {
            throw new RetentionHoldActiveException(contactId, holds);
        }
        int erased = contacts.delete(workspaceId, contactId);
        if (erased == 0) {
            // Already erased by this workspace: the gate let it through, so this is the idempotent
            // repeat rather than a foreign contact. Claim nothing a second time.
            return;
        }
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactErased(workspaceId, contactId, erasedOn)), List.of());
        interests.deleteAllFor(workspaceId, contactId);
        contacts.logErasure(workspaceId, contactId, erasedOn);
    }
}
