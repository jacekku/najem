package pl.najem.contacts.application;

import java.util.List;
import java.util.UUID;

/**
 * The join, done the way the SQL does it (rule 14).
 *
 * <p>It stores nothing. It asks the interest double for the active rows and the people double for
 * each name, which is what makes it an <em>inner</em> join: a contact whose row is gone drops out,
 * exactly as {@code join contacts_person} drops it. A double that cached the name at registration
 * would keep answering after erasure — and would look identical to this one in every test that
 * never erases anybody, which is how a fake starts lying.
 *
 * <p>What it does not model is ordering under the database's collation; the SQL orders by surname
 * and this preserves the interest double's order. A test asserting Ł against L belongs in the
 * container tier (rule 16).
 */
public class InMemoryUnitInterests implements UnitInterestQuery {

    private final InterestRepository interests;
    private final ContactRepository people;

    public InMemoryUnitInterests(InterestRepository interests, ContactRepository people) {
        this.interests = interests;
        this.people = people;
    }

    @Override
    public List<InterestedParty> activeForUnit(UUID workspaceId, UUID unitId) {
        return interests.activeForUnit(workspaceId, unitId).stream()
            .flatMap(interest -> people.find(workspaceId, interest.contactId()).stream()
                .map(details -> new InterestedParty(interest.interestId(), interest.contactId(),
                    details.givenName(), details.surname(), details.email(), details.phone(),
                    interest.willingToPay(), interest.desiredStart())))
            .toList();
    }
}
