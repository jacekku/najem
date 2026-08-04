package pl.najem.pm;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two rules PM must not drift out of: it references people by ContactId and never stores
 * their data, and it does not know another module exists.
 *
 * <p>Both are the kind of rule that is obeyed on the day it is written and eroded by the tenth
 * well-meaning change. Neither is visible in a code review of a single diff.
 */
class ModuleIsolationTest {

    /**
     * Unambiguously personal. {@code name} is deliberately NOT here — see ALLOWED_NAME_COMPONENTS.
     */
    private static final Set<String> PII_COMPONENTS = Set.of(
        "surname", "email", "phone", "phoneNumber", "firstName", "lastName", "fullName",
        "personalName", "pesel", "nip", "idNumber", "dateOfBirth", "bankAccount", "iban",
        "address1", "residentialAddress");

    /**
     * {@code name} on its own is ambiguous: a unit's name is property data, a person's name is
     * PII, and the component reads identically. Rather than rename a stored event component to
     * suit a test — renaming is a breaking change for every payload already written, per the
     * lesson at seq 81 — each legitimate use is listed here by record AND component.
     *
     * <p>Adding an entry is a decision, not a formality: it means a human has looked at the
     * record and judged that the thing being named is not a person. Same shape as reporting's
     * ALLOWED_STREAMS. If you are adding one for a record that describes a human, stop.
     */
    private static final Map<String, Set<String>> ALLOWED_NAME_COMPONENTS = Map.of(
        "UnitAddedToProperty", Set.of("name"));

    @Test
    void pmEventsCarryNoPersonalData() {
        for (Class<?> eventType : PmEventTypes.allRegistered()) {
            for (var component : eventType.getRecordComponents()) {
                assertThat(PII_COMPONENTS)
                    .as("%s.%s looks like personal data — PM references people by ContactId and "
                            + "must never store their details",
                        eventType.getSimpleName(), component.getName())
                    .doesNotContain(component.getName());
            }
        }
    }

    /** The ambiguous ones, checked against the allowlist rather than waved through. */
    @Test
    void everyNameComponentIsAKnownPropertyFactRatherThanAPerson() {
        for (Class<?> eventType : PmEventTypes.allRegistered()) {
            for (var component : eventType.getRecordComponents()) {
                if (!component.getName().equals("name")) {
                    continue;
                }
                assertThat(ALLOWED_NAME_COMPONENTS
                        .getOrDefault(eventType.getSimpleName(), Set.of()))
                    .as("%s.name is not in the allowlist. If it names a unit or a property, add "
                            + "it there deliberately. If it names a person, it does not belong "
                            + "in a PM event at all — carry the ContactId.",
                        eventType.getSimpleName())
                    .contains("name");
            }
        }
    }

    /**
     * Contact ids are how PM refers to people, so any component that carries one must say so in
     * its name. A bare {@code UUID tenant} would be indistinguishable from a tenancy id at the
     * consumer's end, and a consumer that resolves the wrong id shows one person's data for
     * another's — which reads as a rendering bug rather than a privacy breach.
     */
    @Test
    void everyContactReferenceIsNamedAsAContactId() {
        for (Class<?> eventType : PmEventTypes.allRegistered()) {
            for (var component : eventType.getRecordComponents()) {
                String name = component.getName().toLowerCase();
                if (name.contains("tenant") || name.contains("guarantor")) {
                    assertThat(name)
                        .as("%s.%s refers to a person; name it ...ContactId(s) so a consumer "
                                + "cannot mistake it for a tenancy id",
                            eventType.getSimpleName(), component.getName())
                        .contains("contactid");
                }
            }
        }
    }

    /**
     * PM must not be able to see another module's types. This is the rule the build file enforces
     * at compile time; the test exists because a build file is edited by people in a hurry.
     */
    @Test
    void pmDoesNotDependOnAnotherModule() {
        for (String foreign : Set.of(
                "pl.najem.acc.application.LedgerService",
                "pl.najem.contacts.application.ContactService",
                "pl.najem.um.application.WorkspaceService",
                "pl.najem.reporting.application.EventFeed")) {
            assertThatThrownBy(() -> Class.forName(foreign))
                .as("PM can see %s — a module dependency has been added", foreign)
                .isInstanceOf(ClassNotFoundException.class);
        }
    }

    /**
     * Every PM event class must be registered or it will not deserialize — the convention that
     * has already cost one debugging session (Task 2, UnitBaseRentSet). Checking the registry is
     * non-trivially populated is weak; checking that nothing in the event classes is missing is
     * what the module suite already does by exercising every path. This asserts the cheap half:
     * the registry is not silently empty.
     */
    @Test
    void everyRegisteredEventIsARecordOnAPmStream() {
        assertThat(PmEventTypes.allRegistered()).isNotEmpty().allSatisfy(type -> {
            assertThat(type.isRecord())
                .as("%s must be a record — events are values", type.getSimpleName()).isTrue();
            assertThat(type.getName()).startsWith("pl.najem.pm.domain.");
        });
    }
}
