package pl.najem.contacts;

import org.junit.jupiter.api.Test;
import pl.najem.contacts.domain.ContactDetailsCorrected;
import pl.najem.contacts.domain.ContactErased;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.contacts.domain.InterestConverted;
import pl.najem.contacts.domain.InterestRegistered;
import pl.najem.contacts.domain.InterestWithdrawn;
import pl.najem.contacts.domain.LawfulBasisChanged;
import pl.najem.contacts.domain.RetentionHoldReleased;
import pl.najem.contacts.domain.RetentionHoldSet;
import pl.najem.eventstore.EventTypeRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No test in this module pinned the wire name a contacts event is stored and resolved under before
 * this one — {@code EventTypeRegistry} derives it from {@code Class::getSimpleName}, so renaming an
 * event class today is silently also a migration for every row already written with the old name.
 *
 * <p>Rule 12: renaming a constant should stay free, renaming its wire name should not be. This is
 * the simplest test that fails the moment either happens — a class rename, or {@code nameOf}
 * changing to something other than the simple name.
 */
class ContactsWireNamesTest {

    private final EventTypeRegistry registry = new EventTypeRegistry();

    @Test
    void everyContactsEventKeepsTheWireNameAlreadyStoredInProduction() {
        ContactsEventTypes.register(registry);

        assertThat(registry.nameOf(ContactRegistered.class)).isEqualTo("ContactRegistered");
        assertThat(registry.nameOf(ContactDetailsCorrected.class)).isEqualTo("ContactDetailsCorrected");
        assertThat(registry.nameOf(InterestRegistered.class)).isEqualTo("InterestRegistered");
        assertThat(registry.nameOf(InterestWithdrawn.class)).isEqualTo("InterestWithdrawn");
        assertThat(registry.nameOf(InterestConverted.class)).isEqualTo("InterestConverted");
        assertThat(registry.nameOf(LawfulBasisChanged.class)).isEqualTo("LawfulBasisChanged");
        assertThat(registry.nameOf(RetentionHoldSet.class)).isEqualTo("RetentionHoldSet");
        assertThat(registry.nameOf(RetentionHoldReleased.class)).isEqualTo("RetentionHoldReleased");
        assertThat(registry.nameOf(ContactErased.class)).isEqualTo("ContactErased");

        assertThat(registry.resolve("InterestConverted")).isEqualTo(InterestConverted.class);
    }
}
