package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The form's own reading of itself, with nothing booted.
 *
 * <p>Small, and worth having anyway: "did the manager pick somebody, or describe somebody" is the
 * decision the whole screen turns on, and a blank hidden field is the shape it arrives in.
 */
class LeadFormTest {

    @Test
    void ablankChosenIdMeansTheManagerIsDescribingANewPerson() {
        assertThat(UnitScreenController.chosen("")).isEmpty();
        assertThat(UnitScreenController.chosen("   ")).isEmpty();
        assertThat(UnitScreenController.chosen(null)).isEmpty();
    }

    @Test
    void anidMeansTheManagerPickedSomebodyWeAlreadyKnow() {
        var contactId = UUID.randomUUID();

        assertThat(UnitScreenController.chosen(contactId.toString())).contains(contactId);
    }

    /**
     * The field is a hidden input on a form anybody signed in can post. A value that is not a UUID
     * is a bad request (400, mapped by {@link WebErrorAdvice#badContactId()}), not a 500 — and not a
     * silently created duplicate person either. {@link InvalidContactIdException} is thrown rather
     * than the bare {@link IllegalArgumentException} {@code UUID.fromString} raises, so the advice
     * can map exactly this failure without also catching an unrelated bug that happens to throw the
     * same JDK type.
     */
    @Test
    void agarbledIdIsRefusedRatherThanTreatedAsANewPerson() {
        assertThatThrownBy(() -> UnitScreenController.chosen("not-a-uuid"))
            .isInstanceOf(InvalidContactIdException.class);
    }
}
