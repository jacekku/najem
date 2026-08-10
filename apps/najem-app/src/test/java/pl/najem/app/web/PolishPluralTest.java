package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Polish has three plural forms and the rule is on the last two digits.
 *
 * <p>Worth a test rather than a careful reading, because the form that gets left out is the teens
 * exception — 12–14 take the many form despite ending in 2–4 — and it is invisible on the counts a
 * developer types while checking their work (1, 2, 3, 5). A portfolio of twelve flats reading
 * "12 lokale" is wrong Polish on the most ordinary number on the screen.
 *
 * <p>No Spring, no database: this is a pure function on a controller and the test is a few
 * microseconds.
 */
class PolishPluralTest {

    @Test
    void oneTakesTheSingular() {
        assertThat(PropertiesScreenController.polish(1, "lokal", "lokale", "lokali")).isEqualTo("lokal");
    }

    @Test
    void twoToFourTakeTheFewForm() {
        assertThat(PropertiesScreenController.polish(2, "lokal", "lokale", "lokali")).isEqualTo("lokale");
        assertThat(PropertiesScreenController.polish(3, "lokal", "lokale", "lokali")).isEqualTo("lokale");
        assertThat(PropertiesScreenController.polish(4, "lokal", "lokale", "lokali")).isEqualTo("lokale");
    }

    /** The half of the rule that gets left out. 12–14 end in 2–4 and still take the many form. */
    @Test
    void theTeensTakeTheManyFormDespiteEndingInTwoToFour() {
        assertThat(PropertiesScreenController.polish(12, "lokal", "lokale", "lokali")).isEqualTo("lokali");
        assertThat(PropertiesScreenController.polish(13, "lokal", "lokale", "lokali")).isEqualTo("lokali");
        assertThat(PropertiesScreenController.polish(14, "lokal", "lokale", "lokali")).isEqualTo("lokali");
    }

    /** And the twenties resume the few form — 22 is "lokale" again, which is what makes 12 a rule
     *  about the last two digits rather than a special case for one number. */
    @Test
    void countsAboveTwentyEndingInTwoToFourTakeTheFewFormAgain() {
        assertThat(PropertiesScreenController.polish(22, "lokal", "lokale", "lokali")).isEqualTo("lokale");
        assertThat(PropertiesScreenController.polish(103, "lokal", "lokale", "lokali")).isEqualTo("lokale");
    }

    @Test
    void everythingElseTakesTheManyForm() {
        assertThat(PropertiesScreenController.polish(0, "lokal", "lokale", "lokali")).isEqualTo("lokali");
        assertThat(PropertiesScreenController.polish(5, "lokal", "lokale", "lokali")).isEqualTo("lokali");
        assertThat(PropertiesScreenController.polish(11, "lokal", "lokale", "lokali")).isEqualTo("lokali");
        assertThat(PropertiesScreenController.polish(100, "lokal", "lokale", "lokali")).isEqualTo("lokali");
    }

    /**
     * An empty portfolio says "0 nieruchomości", not "0 nieruchomość". Asserted separately because
     * zero is the count this header is most likely to be seen with — a new agency's first visit —
     * and it is the one that falls outside the 1 / 2–4 branches entirely.
     */
    @Test
    void zeroPropertiesReadsAsThePluralNotTheSingular() {
        assertThat(PropertiesScreenController.polish(0, "nieruchomość", "nieruchomości", "nieruchomości"))
            .isEqualTo("nieruchomości");
    }
}
