package pl.najem.contacts.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.contacts.domain.ContactErased;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.contacts.domain.InterestWithdrawn;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contacts rules with no database, in milliseconds.
 *
 * <p>The fast half of the two tiers described in refactoring.md rule 16. The Testcontainers suite
 * beside this one is the authority: <b>when the two disagree the database is right and this file is
 * wrong.</b> What lives here is what the rules decide — the erasure gate, the retention register's
 * key, who may act on whose contact. What must stay over there is anything the store decides:
 * collation and ordering, {@code ilike} semantics, the {@code on conflict} clause as Postgres
 * executes it.
 *
 * <p>Before this file, six of the module's eight test classes booted a container and there was no
 * inner loop at all.
 */
class ContactRulesTest {

    private static final UUID AGENCY = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);

    private InMemoryEventStore store;
    private InMemoryContacts people;
    private InMemoryInterests interestRows;
    private InMemoryRetentionHolds holdRows;
    private ContactDirectory directory;
    private ContactService contacts;
    private InterestService interests;
    private RetentionService retention;
    private UnitInterestQuery interestedParties;

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore();
        people = new InMemoryContacts();
        interestRows = new InMemoryInterests();
        holdRows = new InMemoryRetentionHolds();
        directory = new ContactDirectory(people);
        retention = new RetentionService(store, holdRows,
            new InMemoryErasureDue(people, holdRows), directory);
        contacts = new ContactService(store, people, interestRows, retention, directory);
        interests = new InterestService(store, interestRows, directory);
        interestedParties = new InMemoryUnitInterests(interestRows, people);
    }

    private UUID anna(UUID workspaceId) {
        return anna(workspaceId, null);
    }

    private UUID anna(UUID workspaceId, LocalDate retainUntil) {
        return contacts.register(new NewContact(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", TODAY, retainUntil));
    }

    // ---- the erasure gate -------------------------------------------------

    @Test
    void erasureRemovesThePersonalDataAndKeepsTheStream() {
        var contactId = anna(AGENCY);

        contacts.erase(AGENCY, contactId, TODAY);

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
        assertThat(store.load(contactId, "Contact").events())
            .anySatisfy(e -> assertThat(e).isInstanceOf(ContactErased.class));
    }

    /**
     * The idempotent repeat. It reaches the delete, finds nothing, and returns before appending —
     * so a second erasure is silent rather than a second {@code ContactErased} on the stream.
     *
     * <p>This is the branch {@link ContactRepository#delete} returns a count for. A double that
     * returned {@code void} or an unconditional success could not distinguish the two paths.
     */
    @Test
    void erasingTwiceAppendsOneErasureEvent() {
        var contactId = anna(AGENCY);
        contacts.erase(AGENCY, contactId, TODAY);

        contacts.erase(AGENCY, contactId, TODAY.plusDays(1));

        assertThat(store.load(contactId, "Contact").events())
            .filteredOn(e -> e instanceof ContactErased).hasSize(1);
    }

    /** A 204 means erased. Someone else's contact is a 404, so nobody is told data went that did not. */
    @Test
    void erasingSomeoneElsesContactIsRefused() {
        var contactId = anna(AGENCY);

        assertThatThrownBy(() -> contacts.erase(OTHER, contactId, TODAY))
            .isInstanceOf(NoSuchContactException.class);
        assertThat(directory.find(AGENCY, contactId)).isPresent();
    }

    /** Unknown, foreign and foreign-already-erased are one answer — the gate is not an oracle. */
    @Test
    void aforeignAlreadyErasedContactIsRefusedTheSameWay() {
        var contactId = anna(AGENCY);
        contacts.erase(AGENCY, contactId, TODAY);

        assertThatThrownBy(() -> contacts.erase(OTHER, contactId, TODAY))
            .isInstanceOf(NoSuchContactException.class);
        assertThatThrownBy(() -> contacts.erase(OTHER, UUID.randomUUID(), TODAY))
            .isInstanceOf(NoSuchContactException.class);
    }

    @Test
    void erasureTakesTheInterestsWithIt() {
        var contactId = anna(AGENCY);
        var unitId = UUID.randomUUID();
        interests.register(AGENCY, contactId, unitId, new BigDecimal("3000"), TODAY);

        contacts.erase(AGENCY, contactId, TODAY);

        assertThat(interests.forUnit(AGENCY, unitId)).isEmpty();
    }

    @Test
    void anunreleasedHoldRefusesErasure() {
        var contactId = anna(AGENCY);
        retention.setHold(AGENCY, contactId, "ledger-referenced", TODAY);

        assertThatThrownBy(() -> contacts.erase(AGENCY, contactId, TODAY))
            .isInstanceOf(RetentionHoldActiveException.class);
        assertThat(directory.find(AGENCY, contactId)).isPresent();
    }

    @Test
    void releasingTheLastHoldMakesTheContactErasableAgain() {
        var contactId = anna(AGENCY);
        retention.setHold(AGENCY, contactId, "ledger-referenced", TODAY);
        retention.releaseHold(AGENCY, contactId, "ledger-referenced", TODAY);

        contacts.erase(AGENCY, contactId, TODAY);

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
    }

    // ---- the hold register's key ------------------------------------------

    /**
     * The V41 defect, as a test. Keyed (contact, reason) a contact who is tenant or guarantor on two
     * tenancies has one row for "ledger-referenced", the first tenancy to close releases it, and the
     * person becomes erasable while the second tenancy's ledger is still live — no error, no warning.
     */
    @Test
    void releasingOneSourcesHoldLeavesAnotherSourcesHoldStanding() {
        var contactId = anna(AGENCY);
        var firstTenancy = UUID.randomUUID().toString();
        var secondTenancy = UUID.randomUUID().toString();
        retention.setHold(AGENCY, contactId, "ledger-referenced", firstTenancy, TODAY);
        retention.setHold(AGENCY, contactId, "ledger-referenced", secondTenancy, TODAY);

        retention.releaseHold(AGENCY, contactId, "ledger-referenced", firstTenancy, TODAY);

        assertThat(retention.hasActiveHold(AGENCY, contactId)).isTrue();
        assertThatThrownBy(() -> contacts.erase(AGENCY, contactId, TODAY))
            .isInstanceOf(RetentionHoldActiveException.class);
    }

    /** A trigger re-fanning-out over a changed roster is normal traffic, not an error. */
    @Test
    void reassertingAHoldIsIdempotentAndRaisesAReleasedOne() {
        var contactId = anna(AGENCY);
        var tenancy = UUID.randomUUID().toString();
        retention.setHold(AGENCY, contactId, "ledger-referenced", tenancy, TODAY);
        retention.releaseHold(AGENCY, contactId, "ledger-referenced", tenancy, TODAY);

        retention.setHold(AGENCY, contactId, "ledger-referenced", tenancy, TODAY.plusDays(1));

        assertThat(retention.activeHolds(AGENCY, contactId)).containsExactly("ledger-referenced");
    }

    /** A caller needs causes, not rows: two sources of one reason is one reason. */
    @Test
    void activeHoldsReportsEachReasonOnce() {
        var contactId = anna(AGENCY);
        retention.setHold(AGENCY, contactId, "ledger-referenced", "t1", TODAY);
        retention.setHold(AGENCY, contactId, "ledger-referenced", "t2", TODAY);
        retention.setHold(AGENCY, contactId, "dispute", "manual", TODAY);

        assertThat(retention.activeHolds(AGENCY, contactId))
            .containsExactly("dispute", "ledger-referenced");
    }

    // ---- the report and the gate must agree -------------------------------

    @Test
    void acontactPastItsRetentionDateIsDueForErasure() {
        var contactId = anna(AGENCY, TODAY.minusDays(1));

        assertThat(retention.dueForErasure(AGENCY, TODAY)).containsExactly(contactId);
    }

    @Test
    void acontactStillWithinItsRetentionDateIsNotDue() {
        anna(AGENCY, TODAY.plusDays(1));

        assertThat(retention.dueForErasure(AGENCY, TODAY)).isEmpty();
    }

    /**
     * The disagreement that mattered: this report and {@link RetentionService#hasActiveHold} are two
     * statements of one rule, and when they diverged the failure was toward keeping personal data
     * past its retention date while telling the operator there was nothing to erase.
     */
    @Test
    void aheldContactIsNeitherDueNorErasable() {
        var contactId = anna(AGENCY, TODAY.minusDays(1));
        retention.setHold(AGENCY, contactId, "ledger-referenced", TODAY);

        assertThat(retention.dueForErasure(AGENCY, TODAY)).isEmpty();
        assertThat(retention.hasActiveHold(AGENCY, contactId)).isTrue();
    }

    /** A hold raised in another agency is not a hold here — and cannot be raised in the first place. */
    @Test
    void thedueReportIsScopedToTheWorkspace() {
        var contactId = anna(AGENCY, TODAY.minusDays(1));

        assertThat(retention.dueForErasure(OTHER, TODAY)).isEmpty();
        assertThat(retention.dueForErasure(AGENCY, TODAY)).containsExactly(contactId);
    }

    // ---- who may act on whose contact -------------------------------------

    /**
     * Every command that names a contact goes through the same gate. Enumerated rather than sampled,
     * because the one command that is <em>not</em> fronted by {@code requireIn} is withdrawal, and
     * that asymmetry is only safe while its own lookup names the workspace.
     */
    @Test
    void nocommandOnSomeoneElsesContactIsAccepted() {
        var contactId = anna(AGENCY);
        var details = new ContactDetails("Zmieniona", "Kowalska", null, null);

        assertThatThrownBy(() -> contacts.correctDetails(OTHER, contactId, details, TODAY))
            .isInstanceOf(NoSuchContactException.class);
        assertThatThrownBy(() -> contacts.erase(OTHER, contactId, TODAY))
            .isInstanceOf(NoSuchContactException.class);
        assertThatThrownBy(() -> retention.setHold(OTHER, contactId, "dispute", TODAY))
            .isInstanceOf(NoSuchContactException.class);
        assertThatThrownBy(() -> retention.releaseHold(OTHER, contactId, "dispute", TODAY))
            .isInstanceOf(NoSuchContactException.class);
        assertThatThrownBy(() -> interests.register(OTHER, contactId, UUID.randomUUID(),
            new BigDecimal("3000"), TODAY)).isInstanceOf(NoSuchContactException.class);
    }

    /** Refused before anything is appended, not merely before the SQL write. */
    @Test
    void arefusedCommandLeavesNothingOnTheStream() {
        var contactId = anna(AGENCY);
        var before = store.load(contactId, "Contact").version();

        assertThatThrownBy(() -> retention.setHold(OTHER, contactId, "dispute", TODAY))
            .isInstanceOf(NoSuchContactException.class);

        assertThat(store.load(contactId, "Contact").version()).isEqualTo(before);
    }

    /**
     * Withdrawal's gate is the lookup itself. A foreign interest finds no contact, so the command is
     * refused — and reports absence rather than throwing a data-access exception, which reached the
     * edge as a 500 and made an ordinary "not yours" look like an outage.
     */
    @Test
    void withdrawingSomeoneElsesInterestIsRefusedAndChangesNothing() {
        var contactId = anna(AGENCY);
        var unitId = UUID.randomUUID();
        var interestId = interests.register(AGENCY, contactId, unitId, new BigDecimal("3000"), TODAY);

        assertThatThrownBy(() -> interests.withdraw(OTHER, interestId, TODAY))
            .isInstanceOf(NoSuchInterestException.class);
        assertThat(interests.forUnit(AGENCY, unitId)).hasSize(1);
    }

    @Test
    void withdrawingAnInterestTakesItOffTheUnitAndRecordsIt() {
        var contactId = anna(AGENCY);
        var unitId = UUID.randomUUID();
        var interestId = interests.register(AGENCY, contactId, unitId, new BigDecimal("3000"), TODAY);

        interests.withdraw(AGENCY, interestId, TODAY);

        assertThat(interests.forUnit(AGENCY, unitId)).isEmpty();
        assertThat(store.load(contactId, "Contact").events())
            .anySatisfy(e -> assertThat(e).isInstanceOf(InterestWithdrawn.class));
    }

    @Test
    void interestsAreScopedToTheWorkspaceThatRegisteredThem() {
        var contactId = anna(AGENCY);
        var unitId = UUID.randomUUID();
        interests.register(AGENCY, contactId, unitId, new BigDecimal("3000"), TODAY);

        assertThat(interests.forUnit(OTHER, unitId)).isEmpty();
    }

    // ---- search is a browse aid -------------------------------------------

    @Test
    void ablankSearchTermReturnsNobodyRatherThanEverybody() {
        anna(AGENCY);

        assertThat(directory.search(AGENCY, "")).isEmpty();
        assertThat(directory.search(AGENCY, "   ")).isEmpty();
        assertThat(directory.search(AGENCY, null)).isEmpty();
    }

    /** A term containing {@code %} is a term, not a wildcard that returns the whole directory. */
    @Test
    void awildcardInTheTermIsACharacter() {
        anna(AGENCY);

        assertThat(directory.search(AGENCY, "%")).isEmpty();
        assertThat(directory.search(AGENCY, "_")).isEmpty();
    }

    @Test
    void searchMatchesAcrossGivenNameAndSurnameAndTheTwoJoined() {
        var contactId = anna(AGENCY);

        assertThat(directory.search(AGENCY, "Anna")).extracting(ContactMatch::contactId)
            .containsExactly(contactId);
        assertThat(directory.search(AGENCY, "Kowal")).extracting(ContactMatch::contactId)
            .containsExactly(contactId);
        assertThat(directory.search(AGENCY, "Anna Kowal")).extracting(ContactMatch::contactId)
            .containsExactly(contactId);
    }

    @Test
    void searchDoesNotCrossTheAgencyBoundary() {
        anna(AGENCY);

        assertThat(directory.search(OTHER, "Kowalska")).isEmpty();
    }

    /**
     * Erasure is complete the instant it happens, with no projector to catch up. This is why the
     * people half of search is served from this module and not from Reporting.
     */
    @Test
    void anerasedPersonLeavesSearchImmediately() {
        var contactId = anna(AGENCY);
        contacts.erase(AGENCY, contactId, TODAY);

        assertThat(directory.search(AGENCY, "Kowalska")).isEmpty();
        assertThat(directory.findByEmail(AGENCY, "anna@example.com")).isEmpty();
    }

    @Test
    void correctingDetailsChangesWhatSearchFinds() {
        var contactId = anna(AGENCY);

        contacts.correctDetails(AGENCY, contactId,
            new ContactDetails("Anna", "Nowak", "anna@example.com", null), TODAY);

        assertThat(directory.search(AGENCY, "Kowalska")).isEmpty();
        assertThat(directory.search(AGENCY, "Nowak")).extracting(ContactMatch::contactId)
            .containsExactly(contactId);
    }

    // ---- who is interested in a unit ---------------------------------------

    @Test
    void anInterestedPartyCarriesTheNameFromThePersonTable() {
        var unit = UUID.randomUUID();
        var contactId = anna(AGENCY);
        var interestId = interests.register(AGENCY, contactId, unit,
            new BigDecimal("2900.00"), LocalDate.of(2026, 9, 1));

        assertThat(interestedParties.activeForUnit(AGENCY, unit)).containsExactly(
            new InterestedParty(interestId, contactId, "Anna", "Kowalska",
                "anna@example.com", "+48600100200",
                new BigDecimal("2900.00"), LocalDate.of(2026, 9, 1)));
    }

    @Test
    void awithdrawnInterestLeavesTheUnitsList() {
        var unit = UUID.randomUUID();
        var contactId = anna(AGENCY);
        var interestId = interests.register(AGENCY, contactId, unit, null, null);

        interests.withdraw(AGENCY, interestId, TODAY);

        assertThat(interestedParties.activeForUnit(AGENCY, unit)).isEmpty();
    }

    /**
     * The join is an inner one, and this is the assertion that says so. Erasure deletes the person
     * row and the interests together, so the two are normally consistent — but a double that
     * carried its own copy of the name would keep answering after the person was gone, which is
     * the one failure a PII lookaside exists to prevent.
     */
    @Test
    void anerasedPersonIsNotAnInterestedParty() {
        var unit = UUID.randomUUID();
        var contactId = anna(AGENCY);
        interests.register(AGENCY, contactId, unit, null, null);

        contacts.erase(AGENCY, contactId, TODAY);

        assertThat(interestedParties.activeForUnit(AGENCY, unit)).isEmpty();
    }

    @Test
    void aninterestInanotherWorkspacesUnitIsNotVisible() {
        var unit = UUID.randomUUID();
        var contactId = anna(AGENCY);
        interests.register(AGENCY, contactId, unit, null, null);

        assertThat(interestedParties.activeForUnit(OTHER, unit)).isEmpty();
    }

    // ---- registering the person who phoned ---------------------------------

    @Test
    void aleadIsRegisteredUnderLegitimateInterest() {
        var contactId = contacts.registerLead(AGENCY,
            new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"), true, TODAY);

        assertThat(store.load(contactId, "Contact").events()).containsExactly(
            new ContactRegistered(AGENCY, contactId, "legitimate-interest", TODAY, null));
    }

    /**
     * The clause is the one fact on that form which is the manager's to assert. Recording today
     * regardless would have the application claim it was read out when nobody said so.
     */
    @Test
    void anunservedInfoClauseIsRecordedAsAbsentRatherThanAsToday() {
        var contactId = contacts.registerLead(AGENCY,
            new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"), false, TODAY);

        assertThat(store.load(contactId, "Contact").events()).containsExactly(
            new ContactRegistered(AGENCY, contactId, "legitimate-interest", null, null));
    }

    @Test
    void aleadIsFindableInTheDirectoryLikeAnyOtherPerson() {
        var contactId = contacts.registerLead(AGENCY,
            new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"), true, TODAY);

        assertThat(directory.find(AGENCY, contactId))
            .contains(new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"));
    }

    // ---- lawful basis, on the in-memory double itself -----------------------

    /**
     * Drives {@link InMemoryContacts#updateLawfulBasis} end to end: {@code lawfulBasisOf} must read
     * back what the update actually wrote, not a constant and not a value re-derived some other way
     * (rule 14).
     */
    @Test
    void aLeadWhoSignsBecomesAContractPartyInTheDouble() {
        var contactId = contacts.registerLead(AGENCY,
            new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"), true, TODAY);

        contacts.becameContractParty(AGENCY, contactId, TODAY);

        assertThat(people.lawfulBasisOf(AGENCY, contactId)).contains("contract");
    }

    @Test
    void aContractPartyWhoIsAlreadyOneRecordsNothingOnTheFastTier() {
        var guarantor = contacts.registerParty(AGENCY,
            new ContactDetails("Anna", "Zielinska", "a@example.com", "+48"), true, TODAY);
        var before = store.load(guarantor, "Contact").events().size();

        contacts.becameContractParty(AGENCY, guarantor, TODAY);

        assertThat(store.load(guarantor, "Contact").events()).hasSize(before);
    }

    /** The double's own workspace scoping, not the service's — {@code mine(...)} filters like {@code find}. */
    @Test
    void lawfulBasisOfAForeignContactIsEmpty() {
        var contactId = contacts.registerLead(AGENCY,
            new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"), true, TODAY);

        assertThat(people.lawfulBasisOf(OTHER, contactId)).isEmpty();
    }

    // ---- converting an interest, on the in-memory double itself -------------

    /** No fast-tier test previously drove {@link InMemoryInterests#convert}; closed as a deferred minor. */
    @Test
    void convertingAnInterestClosesItAndRefusesASecondConversion() {
        var unitId = UUID.randomUUID();
        var contactId = anna(AGENCY);
        var interestId = interests.register(AGENCY, contactId, unitId, new BigDecimal("2900"), TODAY);
        var tenancyId = UUID.randomUUID();

        interests.convert(AGENCY, interestId, tenancyId, TODAY);

        assertThat(interests.forUnit(AGENCY, unitId)).isEmpty();
        assertThatThrownBy(() -> interests.convert(AGENCY, interestId, UUID.randomUUID(), TODAY.plusDays(1)))
            .isInstanceOf(InterestNotActiveException.class);
    }
}
