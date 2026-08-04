package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenancyDocumentTest {

    private final UUID tenancyId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private ArrayList<Object> reserved(LegalForm legalForm) {
        return new ArrayList<>(Tenancy.reserve(new ReserveTenancy(tenancyId, workspaceId,
            UUID.randomUUID(), List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 9, 1),
            new Term.FixedTerm(LocalDate.of(2027, 8, 31)), legalForm,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, new BigDecimal("2500"),
            "NAJEM/A")));
    }

    private ArrayList<Object> active() {
        var history = reserved(LegalForm.ZWYKLY);
        history.addAll(Tenancy.from(history).activate(LocalDate.of(2026, 9, 1)));
        return history;
    }

    @Test
    void insurancePolicyExpiryIsTracked() {
        var history = active();
        history.addAll(Tenancy.from(history).attachDocument(DocType.INSURANCE_POLICY,
            "s3://docs/oc.pdf", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31),
            LocalDate.of(2026, 8, 20)));

        assertThat(Tenancy.from(history).insuranceExpiry()).contains(LocalDate.of(2027, 8, 31));
    }

    /** A renewed policy supersedes the old one — the latest expiry is the one that matters. */
    @Test
    void arenewedPolicyReplacesTheExpiryOfTheOldOne() {
        var history = active();
        history.addAll(Tenancy.from(history).attachDocument(DocType.INSURANCE_POLICY,
            "s3://docs/oc-2026.pdf", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31),
            LocalDate.of(2026, 8, 20)));
        history.addAll(Tenancy.from(history).attachDocument(DocType.INSURANCE_POLICY,
            "s3://docs/oc-2027.pdf", LocalDate.of(2027, 9, 1), LocalDate.of(2028, 8, 31),
            LocalDate.of(2027, 8, 20)));

        assertThat(Tenancy.from(history).insuranceExpiry()).contains(LocalDate.of(2028, 8, 31));
    }

    @Test
    void anAgreementWithNoValidityPeriodIsNotAnInsuranceExpiry() {
        var history = active();
        history.addAll(Tenancy.from(history).attachDocument(DocType.AGREEMENT,
            "s3://docs/umowa.pdf", null, null, LocalDate.of(2026, 8, 20)));

        assertThat(Tenancy.from(history).insuranceExpiry()).isEmpty();
    }

    @Test
    void notarialDeclarationGatesAutoActivationForInstytucjonalny() {
        var history = reserved(LegalForm.INSTYTUCJONALNY);

        assertThat(Tenancy.from(history).autoActivationAllowed()).isFalse();

        history.addAll(Tenancy.from(history).attachDocument(DocType.NOTARIAL_DECLARATION,
            "s3://docs/akt.pdf", null, null, LocalDate.of(2026, 8, 20)));

        assertThat(Tenancy.from(history).autoActivationAllowed()).isTrue();
    }

    /** The gate is for instytucjonalny only — an ordinary tenancy never needed the declaration. */
    @Test
    void anordinaryTenancyAutoActivatesWithoutADeclaration() {
        assertThat(Tenancy.from(reserved(LegalForm.ZWYKLY)).autoActivationAllowed()).isTrue();
    }

    @Test
    void commentsAndCorrectionsAreAppendOnly() {
        var history = active();
        history.addAll(Tenancy.from(history).addComment("annex: parking spot included from Jan"));
        history.addAll(Tenancy.from(history)
            .correctDetails(Map.of("paymentReference", "NAJEM/12/2026/A-KOW2")));

        assertThat(Tenancy.from(history).paymentReference()).isEqualTo("NAJEM/12/2026/A-KOW2");
        assertThat(Tenancy.from(history).comments()).containsExactly(
            "annex: parking spot included from Jan");
    }

    /** A mistyped correction key must fail loudly, not silently correct nothing. */
    @Test
    void anUnknownCorrectionKeyIsRejected() {
        var history = active();

        assertThatThrownBy(() -> Tenancy.from(history).correctDetails(Map.of("montlyTotal", "2600")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("montlyTotal");
    }

    @Test
    void correctingSeveralFieldsAtOnceAppliesAllOfThem() {
        var history = active();
        history.addAll(Tenancy.from(history).correctDetails(Map.of(
            "rentDay", "5", "startDate", "2026-09-15")));

        var tenancy = Tenancy.from(history);
        assertThat(tenancy.rentDay()).isEqualTo(5);
        assertThat(tenancy.startDate()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    /**
     * paymentReference and monthlyTotal were both published to Accounting at activation, so
     * correcting one here leaves their ledger holding a stale value. PM cannot fix that alone —
     * it needs a contract record — so until then the correction warns rather than passing
     * silently. A correction nobody downstream hears about is the shape of bug this codebase
     * keeps finding: no exception, no log, just two modules quietly disagreeing.
     */
    @Test
    void correctingAFactAlreadyPublishedToAccountingWarns() {
        var history = active();
        history.addAll(Tenancy.from(history)
            .correctDetails(Map.of("paymentReference", "NAJEM/12/2026/A-KOW2")));

        assertThat(Tenancy.from(history).warnings().messages())
            .anyMatch(m -> m.contains("paymentReference") && m.contains("Accounting"));
    }

    /** rentDay is PM's own; nobody downstream holds it, so correcting it warns nobody. */
    @Test
    void correctingAPrivateFactDoesNotWarn() {
        var history = active();
        history.addAll(Tenancy.from(history).correctDetails(Map.of("rentDay", "5")));

        assertThat(Tenancy.from(history).warnings().messages())
            .noneMatch(m -> m.contains("Accounting"));
    }

    /** Before activation nothing has been published, so a correction is just a correction. */
    @Test
    void correctingBeforeActivationDoesNotWarn() {
        var history = reserved(LegalForm.ZWYKLY);
        history.addAll(Tenancy.from(history)
            .correctDetails(Map.of("paymentReference", "NAJEM/12/2026/A-KOW2")));

        assertThat(Tenancy.from(history).warnings().messages())
            .noneMatch(m -> m.contains("Accounting"));
    }
}
