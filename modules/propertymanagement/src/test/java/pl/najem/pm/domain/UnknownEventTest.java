package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An event type nobody registered is this application's bug, not the manager's request.
 *
 * <p>Fast tier, and the second assertion in each test is the one that earns its keep. Every rebuild
 * loop's {@code default} branch used to throw {@link IllegalArgumentException}, and every screen in
 * this application converts a bare {@code IllegalArgumentException} out of a PM command into a 400 —
 * because that is what the creation guards raise. So a forgotten {@code @JsonSubTypes} entry reached
 * a manager as "your request was bad", naming a Java class. {@link UnknownEventException} is
 * deliberately not one, so nothing maps it and it surfaces as the 500 it is.
 *
 * <p>Asserting only {@code isInstanceOf(UnknownEventException.class)} would stay green if somebody
 * made it extend {@code IllegalArgumentException} tomorrow, which is precisely the change that
 * silently undoes the point. {@code isNotInstanceOf} is what refuses it.
 *
 * <p>All four aggregates, because the rule is about the loop and there are four loops. A guarantee
 * that holds for the two somebody remembered is the procedural invariant refactoring rule 9 exists
 * to replace.
 */
class UnknownEventTest {

    @Test
    void apropertyRefusesAnEventItDoesNotKnow() {
        assertThatThrownBy(() -> Property.from(List.of("nonsense")))
            .isInstanceOf(UnknownEventException.class)
            .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aunitRefusesAnEventItDoesNotKnow() {
        assertThatThrownBy(() -> Unit.from(List.of("nonsense")))
            .isInstanceOf(UnknownEventException.class)
            .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atenancyRefusesAnEventItDoesNotKnow() {
        assertThatThrownBy(() -> Tenancy.from(List.of("nonsense")))
            .isInstanceOf(UnknownEventException.class)
            .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arepairRefusesAnEventItDoesNotKnow() {
        assertThatThrownBy(() -> Repair.from(List.of("nonsense")))
            .isInstanceOf(UnknownEventException.class)
            .isNotInstanceOf(IllegalArgumentException.class);
    }

    /**
     * And it is not a {@link RuntimeException} subclass that some other handler already catches.
     * Named explicitly because "unmapped" is the whole design and is otherwise only provable by
     * booting the web layer.
     */
    @Test
    void theRefusalNamesTheTypeThatWasNotUnderstood() {
        assertThatThrownBy(() -> Property.from(List.of("nonsense")))
            .hasMessageContaining("String");
    }
}
