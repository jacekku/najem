package pl.najem.contacts.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contacts.domain.InterestConverted;
import pl.najem.contacts.domain.InterestRegistered;
import pl.najem.contacts.domain.InterestWithdrawn;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class InterestService {

    private final EventStore store;
    private final InterestRepository interests;
    private final ContactDirectory directory;

    public InterestService(EventStore store, InterestRepository interests, ContactDirectory directory) {
        this.store = store;
        this.interests = interests;
        this.directory = directory;
    }

    public UUID register(UUID workspaceId, UUID contactId, UUID unitId,
                         BigDecimal willingToPay, LocalDate desiredStart) {
        directory.requireIn(workspaceId, contactId);
        UUID interestId = UUID.randomUUID();
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestRegistered(workspaceId, interestId, contactId, unitId,
                willingToPay, desiredStart)), List.of());
        interests.insert(interestId, workspaceId, contactId, unitId, willingToPay, desiredStart);
        return interestId;
    }

    /**
     * The lookup is the workspace gate: it names the workspace, so a foreign or unknown interest
     * finds nothing and the command is refused before anything is appended. See
     * {@link InterestRepository#find} for why it reports absence rather than throwing.
     */
    public void withdraw(UUID workspaceId, UUID interestId, LocalDate withdrawnOn) {
        var interest = requireActive(workspaceId, interestId);
        UUID contactId = interest.contactId();
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestWithdrawn(workspaceId, interestId, contactId, withdrawnOn)), List.of());
        interests.withdraw(workspaceId, interestId);
    }

    /**
     * The lead said yes and the agreement was signed.
     *
     * <p>Called after the reservation exists, never before: converting first would mark a lead as won
     * for a tenancy that does not exist, and nothing would show it.
     */
    public void convert(UUID workspaceId, UUID interestId, UUID tenancyId, LocalDate convertedOn) {
        var interest = requireActive(workspaceId, interestId);
        UUID contactId = interest.contactId();
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestConverted(workspaceId, interestId, contactId, interest.unitId(),
                tenancyId, convertedOn)), List.of());
        interests.convert(workspaceId, interestId, tenancyId);
    }

    /**
     * One lookup answering both questions every command on an interest has to ask: is it yours, and
     * is it still open. Shared so that a future command cannot get the second one wrong by omission.
     */
    private Interest requireActive(UUID workspaceId, UUID interestId) {
        var interest = interests.find(workspaceId, interestId)
            .orElseThrow(() -> new NoSuchInterestException(interestId));
        if (!"active".equals(interest.status())) {
            throw new InterestNotActiveException(interestId, interest.status());
        }
        return interest;
    }

    public List<Interest> forUnit(UUID workspaceId, UUID unitId) {
        return interests.activeForUnit(workspaceId, unitId);
    }

    public List<Object> eventsFor(UUID contactId) {
        return List.copyOf(store.load(contactId, "Contact").events());
    }
}
