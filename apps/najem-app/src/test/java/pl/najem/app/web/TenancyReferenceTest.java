package pl.najem.app.web;

import org.junit.jupiter.api.Test;
import pl.najem.pm.application.TenancyDetailRow;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.Tenancy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract reference, pinned without booting anything.
 *
 * <p>Fast tier on purpose — {@code reference} is a pure function of three fields, and the
 * {@code SearchGroupingTest}/{@code LeadFormTest} precedent is that logic which does not need a
 * container to be trusted does not get one. Every case below is a rendering decision somebody could
 * "tidy up" without realising a manager quotes this string on the phone.
 */
class TenancyReferenceTest {

    /**
     * The shape, end to end: year from the start date, street from the address, unit verbatim.
     *
     * <p>{@code Hoża} is the case that mattered. The first version reduced by DELETION, the way
     * {@link PaymentReferences} does — {@code Ż} is not in {@code A-Z}, so it vanished and the
     * reference read {@code HOA42}. Transliteration is the fix, and this assertion is what stops it
     * being reverted to "the same reduction we already have".
     */
    @Test
    void polishLettersAreTransliteratedRatherThanDropped() {
        assertThat(TenancyScreenController.reference(row("ul. Hoża 42, 00-516 Warszawa", "2A")))
            .isEqualTo("NJ-2026/HOZA42-2A");
    }

    /**
     * {@code Ł} needs its own case and not just its own line of code.
     *
     * <p>It is the one Polish letter that is not a base letter plus a combining mark, so NFD
     * decomposition leaves it intact and a {@code \p{M}} strip cannot reach it — a transliteration
     * written as "normalise and strip marks", which is the obvious implementation and looks
     * complete, drops it entirely and turns {@code Łucka} into {@code UCKA}.
     */
    @Test
    void theOneLetterNormalisationCannotReachIsHandled() {
        assertThat(TenancyScreenController.reference(row("ul. Łucka 18, 00-845 Warszawa", "3/2")))
            .isEqualTo("NJ-2026/LUCKA18-32");
    }

    /**
     * The postcode and the city do not reach the reference.
     *
     * <p>They did, and the result was {@code ULHOA420}: eight characters of which two were the
     * street type and three were half a postcode. Asserted on an address with a LONG street name so
     * the ten-character cap is what truncates, and a regression that put the city back would still
     * change the answer.
     */
    @Test
    void onlyTheStreetAndNumberSurvive() {
        assertThat(TenancyScreenController.reference(row("aleja Niepodległości 210, 00-608 Warszawa", "12")))
            .isEqualTo("NJ-2026/NIEPODLEGL-12");
    }

    /**
     * An address that reduces to nothing falls back to a word rather than to an empty segment.
     *
     * <p>{@code NJ-2026/-2A} reads as a rendering fault; {@code NJ-2026/ADRES-2A} reads as an
     * address nobody entered, which is what it is. The same call {@link PaymentReferences} makes for
     * its own {@code UNNAMED}.
     */
    @Test
    void anAddressOfPurePunctuationFallsBackRatherThanLeavingAHole() {
        assertThat(TenancyScreenController.reference(row("—, 00-000", "—")))
            .isEqualTo("NJ-2026/ADRES-LOKAL");
    }

    /**
     * The year is the tenancy's START, not today.
     *
     * <p>A reference that changed when the calendar did would be a reference nobody could quote
     * twice, which is the one property that makes it usable at all.
     */
    @Test
    void theYearComesFromTheContractAndNotFromTheClock() {
        var row = row("ul. Prosta 5, 00-001 Warszawa", "1");
        assertThat(TenancyScreenController.reference(row)).startsWith("NJ-2026/");

        var older = new TenancyDetailRow(row.tenancyId(), row.unitId(), row.unitName(),
            row.propertyAddress(), row.state(), LocalDate.of(2019, 3, 1), row.endDate(),
            row.legalForm(), row.monthlyTotal(), row.rent(), row.adminFee(), row.mediaAdvance(),
            row.componentSplit(), row.rentDay(), row.depositAmount(), row.paymentReference(),
            row.tenantContactIds(), row.guarantorContactIds());
        assertThat(TenancyScreenController.reference(older)).startsWith("NJ-2019/");
    }

    private static TenancyDetailRow row(String address, String unitName) {
        return new TenancyDetailRow(UUID.randomUUID(), UUID.randomUUID(), unitName, address,
            Tenancy.State.ACTIVE, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31),
            LegalForm.ZWYKLY, new BigDecimal("4540"), null, null, null, false, 10,
            new BigDecimal("7800"), "NAJEM/X/2026-09", List.of(), List.of());
    }
}
