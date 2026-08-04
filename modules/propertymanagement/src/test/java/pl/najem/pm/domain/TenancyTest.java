package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenancyTest {

    private final UUID tenancyId = UUID.randomUUID();
    private final UUID unitId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID annaId = UUID.randomUUID();
    private final UUID partnerId = UUID.randomUUID();
    private final UUID guarantorId = UUID.randomUUID();

    private ReserveTenancy command() {
        return new ReserveTenancy(tenancyId, workspaceId, unitId,
            List.of(annaId), List.of(guarantorId),
            LocalDate.of(2026, 9, 1), new Term.FixedTerm(LocalDate.of(2027, 8, 31)),
            LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"),
                new MonthlyAmount.Breakdown(new BigDecimal("2200"), new BigDecimal("150"),
                    new BigDecimal("150"))),
            10, new BigDecimal("5000"), "NAJEM/12/2026/A-KOW");
    }

    private ArrayList<Object> reserved() {
        return new ArrayList<>(Tenancy.reserve(command()));
    }

    @Test
    void reservationCapturesTheWholeAgreement() {
        var tenancy = Tenancy.from(reserved());

        assertThat(tenancy.state()).isEqualTo(Tenancy.State.RESERVED);
        assertThat(tenancy.tenantContactIds()).containsExactly(annaId);
        assertThat(tenancy.guarantorContactIds()).containsExactly(guarantorId);
        assertThat(tenancy.monthly().total()).isEqualByComparingTo("2500");
        assertThat(tenancy.legalForm()).isEqualTo(LegalForm.ZWYKLY);
        assertThat(tenancy.endDate()).isEqualTo(LocalDate.of(2027, 8, 31));
        assertThat(tenancy.rentDay()).isEqualTo(10);
        assertThat(tenancy.depositAmount()).isEqualByComparingTo("5000");
        assertThat(tenancy.warnings().isEmpty()).isTrue();
    }

    @Test
    void aTenancyNeedsAtLeastOneTenantContact() {
        var noTenants = new ReserveTenancy(tenancyId, workspaceId, unitId, List.of(), List.of(),
            LocalDate.of(2026, 9, 1), new Term.Indefinite(), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null, "NAJEM/A");

        assertThatThrownBy(() -> Tenancy.reserve(noTenants))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one tenant");
    }

    @Test
    void indefiniteTermHasNoEndDate() {
        var indefinite = reserveWith(LegalForm.ZWYKLY, new BigDecimal("2500"), new Term.Indefinite());

        assertThat(Tenancy.from(Tenancy.reserve(indefinite)).endDate()).isNull();
    }

    @Test
    void breakdownNotSummingToTheTotalWarnsButDoesNotReject() {
        var command = new ReserveTenancy(tenancyId, workspaceId, unitId, List.of(annaId), List.of(),
            LocalDate.of(2026, 9, 1), new Term.Indefinite(), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"),
                new MonthlyAmount.Breakdown(new BigDecimal("2200"), new BigDecimal("150"),
                    new BigDecimal("100"))),
            10, new BigDecimal("2500"), "NAJEM/12/2026/A-KOW");

        var tenancy = Tenancy.from(Tenancy.reserve(command));

        assertThat(tenancy.warnings().messages())
            .contains("Breakdown totals 2450 but monthly total is 2500");
    }

    @Test
    void depositAboveTheStatutoryCapWarnsPerLegalForm() {
        // zwykly: 12x cap — 40000 is over 12 x 2500 = 30000
        assertThat(warningsOf(reserveWith(LegalForm.ZWYKLY, new BigDecimal("40000"))))
            .anyMatch(m -> m.startsWith("Deposit 40000 exceeds"));

        // instytucjonalny: 6x (art. 19f ust. 5, raised from 3x by the 2019 KZN amendment)
        // — 20000 is under 12x but over 6 x 2500 = 15000
        assertThat(warningsOf(reserveWith(LegalForm.INSTYTUCJONALNY, new BigDecimal("20000"))))
            .anyMatch(m -> m.startsWith("Deposit 20000 exceeds"));

        // the same 20000 under zwykly is lawful — the cap must not be flat
        assertThat(warningsOf(reserveWith(LegalForm.ZWYKLY, new BigDecimal("20000"))))
            .noneMatch(m -> m.startsWith("Deposit"));
    }

    @Test
    void fixedTermLongerThanTenYearsWarns() {
        var longTerm = new ReserveTenancy(tenancyId, workspaceId, unitId, List.of(annaId), List.of(),
            LocalDate.of(2026, 9, 1), new Term.FixedTerm(LocalDate.of(2040, 1, 1)), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null, "NAJEM/A");

        assertThat(warningsOf(longTerm)).anyMatch(m -> m.contains("10 years"));
    }

    @Test
    void tenantsCanBeAddedAndRemoved() {
        var history = reserved();
        history.addAll(Tenancy.from(history).addTenant(partnerId));
        history.addAll(Tenancy.from(history).removeTenant(annaId));

        assertThat(Tenancy.from(history).tenantContactIds()).containsExactly(partnerId);
    }

    @Test
    void reservedTenancyActivates() {
        var events = Tenancy.from(reserved()).activate(LocalDate.of(2026, 9, 1));

        assertThat(events).containsExactly(
            new TenancyEvents.TenancyActivated(workspaceId, tenancyId, LocalDate.of(2026, 9, 1)));
    }

    @Test
    void activatingTwiceIsRejected() {
        var history = reserved();
        history.addAll(Tenancy.from(history).activate(LocalDate.of(2026, 9, 1)));

        assertThatThrownBy(() -> Tenancy.from(history).activate(LocalDate.of(2026, 9, 2)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellingAfterActivationIsRejected() {
        var history = reserved();
        history.addAll(Tenancy.from(history).activate(LocalDate.of(2026, 9, 1)));

        assertThatThrownBy(() -> Tenancy.from(history).cancelReservation("too late"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unsplitMonthlyAmountReportsNoContractualSplit() {
        var noSplit = reserveWith(LegalForm.ZWYKLY, new BigDecimal("2500"), new Term.Indefinite());

        var tenancy = Tenancy.from(Tenancy.reserve(noSplit));

        assertThat(tenancy.monthly().componentSplitInContract()).isFalse();
        assertThat(tenancy.monthly().breakdown()).isNull();
    }

    @Test
    void aSplitWithZeroAdminFeeIsStillASplit() {
        var zeroAdminFee = new ReserveTenancy(tenancyId, workspaceId, unitId, List.of(annaId), List.of(),
            LocalDate.of(2026, 9, 1), new Term.Indefinite(), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"),
                new MonthlyAmount.Breakdown(new BigDecimal("2300"), BigDecimal.ZERO,
                    new BigDecimal("200"))),
            10, null, "NAJEM/A");

        assertThat(Tenancy.from(Tenancy.reserve(zeroAdminFee)).monthly().componentSplitInContract())
            .isTrue();
    }

    private List<String> warningsOf(ReserveTenancy command) {
        return Tenancy.from(Tenancy.reserve(command)).warnings().messages();
    }

    private ReserveTenancy reserveWith(LegalForm legalForm, BigDecimal deposit) {
        return reserveWith(legalForm, deposit, new Term.Indefinite());
    }

    private ReserveTenancy reserveWith(LegalForm legalForm, BigDecimal deposit, Term term) {
        return new ReserveTenancy(tenancyId, workspaceId, unitId, List.of(annaId), List.of(),
            LocalDate.of(2026, 9, 1), term, legalForm,
            new MonthlyAmount(new BigDecimal("2500"), null),
            10, deposit, "NAJEM/12/2026/A-KOW");
    }
}
