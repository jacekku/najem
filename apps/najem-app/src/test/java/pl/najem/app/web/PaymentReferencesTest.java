package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PaymentReferences}, tested without booting anything — the {@code SearchGroupingTest}
 * precedent.
 */
class PaymentReferencesTest {

    @Test
    void aReferenceNamesTheUnitAndTheMonthItStarts() {
        assertThat(PaymentReferences.suggest("m. 2", LocalDate.of(2026, 9, 1)))
            .isEqualTo("NAJEM/M2/2026-09");
    }

    @Test
    void punctuationAndDiacriticsAreCollapsed() {
        assertThat(PaymentReferences.suggest("lokal A/1", LocalDate.of(2026, 12, 31)))
            .isEqualTo("NAJEM/LOKALA1/2026-12");
    }

    /**
     * Named rather than fixed. Two units with the same name in different properties collide, the
     * manager can see and change the value, and no port exists to ask accounting whether a reference
     * is taken. The test pins the collision so nobody discovers it in production instead.
     */
    @Test
    void twoUnitsNamedAlikeInDifferentPropertiesCollide() {
        assertThat(PaymentReferences.suggest("m. 2", LocalDate.of(2026, 9, 1)))
            .isEqualTo(PaymentReferences.suggest("M2", LocalDate.of(2026, 9, 30)));
    }

    @Test
    void aUnitNamedOnlyInPunctuationStillProducesAReference() {
        assertThat(PaymentReferences.suggest("—", LocalDate.of(2026, 9, 1)))
            .isEqualTo("NAJEM/LOKAL/2026-09");
    }
}
