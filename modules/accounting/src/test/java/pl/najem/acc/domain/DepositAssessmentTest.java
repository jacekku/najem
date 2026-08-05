package pl.najem.acc.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What art. 6 has to say about a deposit, asked without a database.
 *
 * <p>These used to need a container, because the cap rules were inline in the service that writes
 * the row. Nothing here records anything — a cap is a statement about two numbers and a legal form.
 */
class DepositAssessmentTest {

    @Test
    void aDepositWithinTheCapIsNotFlagged() {
        var assessment = assess("6000", "3000", LegalForm.OKAZJONALNY.name());

        assertThat(assessment.capExceeded()).isFalse();
        assertThat(assessment.capCheckable()).isTrue();
        assertThat(assessment.formRecognised()).isTrue();
        assertThat(assessment.multiplier()).isEqualByComparingTo("2");
    }

    @Test
    void aDepositAboveTheCapForItsFormIsFlagged() {
        var okazjonalny = assess("21000", "3000", LegalForm.OKAZJONALNY.name());

        assertThat(okazjonalny.capExceeded()).isTrue();
        assertThat(okazjonalny.multiplier()).isEqualByComparingTo("7");
        assertThat(okazjonalny.capInMonths()).isLessThan(7);
    }

    /** Exactly at the cap is within it: the statute caps the deposit, it does not forbid reaching it. */
    @Test
    void aDepositExactlyAtTheCapIsWithinIt() {
        var assessment = assess("18000", "3000", LegalForm.INSTYTUCJONALNY.name());

        assertThat(assessment.multiplier()).isEqualByComparingTo(
            BigDecimal.valueOf(assessment.capInMonths()));
        assertThat(assessment.capExceeded()).isFalse();
    }

    /**
     * The case the whole {@code capCheckable} flag exists for. A multiplier of zero is not greater
     * than any cap, so a deposit against no czynsz would silently pass a check that never ran —
     * and a contract putting its whole monthly into adminFee and mediaAdvance is exactly how one
     * would be placed beyond the cap's reach.
     */
    @Test
    void aDepositAgainstNoRentIsNotCheckedRatherThanPassing() {
        for (String rent : new String[] {null, "0"}) {
            var assessment = assess("6000", rent, LegalForm.OKAZJONALNY.name());

            assertThat(assessment.capCheckable()).isFalse();
            assertThat(assessment.capExceeded()).isFalse();
            assertThat(assessment.multiplier()).isNull();
        }
    }

    /**
     * Null rather than zero. A stored zero is indistinguishable from a computed one to every later
     * reader — including valorization at return, which works from this same base and would compute
     * a valorized deposit of nothing.
     */
    @Test
    void anUncheckableDepositRecordsNoMultipleRatherThanAMultipleOfZero() {
        assertThat(assess("6000", null, LegalForm.OKAZJONALNY.name()).multiplier()).isNull();
    }

    /**
     * The form arrives from property management as a string on the wire, and PM sends it
     * <strong>lowercase</strong> — {@code TenancyServiceTest} pins {@code "instytucjonalny"} in the
     * payload. Every other test here names the constant, which spells it upper case, so this is the
     * one place the casing that actually crosses the module boundary is exercised. Without it the
     * enum could be renamed to something PM never sends and nothing would fail (rule 12).
     */
    @Test
    void theLowercaseFormPropertyManagementSendsIsRecognised() {
        for (LegalForm form : LegalForm.values()) {
            var assessment = assess("6000", "3000", form.name().toLowerCase());

            assertThat(assessment.formRecognised())
                .as("PM sends %s", form.name().toLowerCase())
                .isTrue();
        }
    }

    @Test
    void anUnrecognisedLegalFormIsSaidRatherThanGuessed() {
        var assessment = assess("6000", "3000", "dzierżawa wieczysta");

        assertThat(assessment.formRecognised()).isFalse();
        assertThat(assessment.capExceeded()).isFalse();
        // The multiple is still computed: it is a fact about the contract, not about the form.
        assertThat(assessment.multiplier()).isEqualByComparingTo("2");
    }

    /** Two places, because it is a snapshot of an agreed term — "two months" reads back as 2.00. */
    @Test
    void theMultipleIsKeptToTwoPlaces() {
        assertThat(assess("5000", "3000", LegalForm.OKAZJONALNY.name()).multiplier()).isEqualByComparingTo("1.67");
    }

    private static DepositAssessment assess(String amount, String rent, String legalForm) {
        return DepositAssessment.assess(new BigDecimal(amount),
            rent == null ? null : new BigDecimal(rent), legalForm);
    }
}
