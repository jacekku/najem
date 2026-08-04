package pl.najem.acc.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Art. 6 ust. 4: the deposit goes back at the agreed multiple of the czynsz in force on the day of
 * return, but never below what was taken. The floor is one-directional on purpose — the tenant
 * carries none of the inflation risk and the landlord none of the deflation risk.
 */
class DepositValorizationTest {

    @Test
    void rentThatRoseCarriesTheDepositUpWithIt() {
        var valorization = DepositValorization.compute(
            new BigDecimal("2.00"), new BigDecimal("6000"), new BigDecimal("3600"));

        assertThat(valorization.valorized()).isEqualByComparingTo("7200.00");
        assertThat(valorization.floorApplied()).isFalse();
    }

    /** The landlord does not profit from a rent reduction at the tenant's expense. */
    @Test
    void rentThatFellLeavesTheTenantWithWhatTheyPaidIn() {
        var valorization = DepositValorization.compute(
            new BigDecimal("2.00"), new BigDecimal("6000"), new BigDecimal("2500"));

        assertThat(valorization.valorized()).isEqualByComparingTo("6000.00");
        assertThat(valorization.floorApplied()).isTrue();
    }

    /** Rent unchanged is the floor doing its job, not a coincidence worth hiding. */
    @Test
    void rentUnchangedReturnsTheNominalAndSaysTheFloorDecidedIt() {
        var valorization = DepositValorization.compute(
            new BigDecimal("2.00"), new BigDecimal("6000"), new BigDecimal("3000"));

        assertThat(valorization.valorized()).isEqualByComparingTo("6000.00");
        assertThat(valorization.floorApplied()).isTrue();
    }

    /**
     * A fractional multiple is an ordinary contract term — one and a half months' rent — and the
     * result is money, so it lands on grosze rather than trailing a fraction of one.
     */
    @Test
    void aFractionalMultipleValorizesToWholeGrosze() {
        var valorization = DepositValorization.compute(
            new BigDecimal("1.50"), new BigDecimal("4500"), new BigDecimal("3333.33"));

        assertThat(valorization.valorized()).isEqualByComparingTo("5000.00");
    }

    /**
     * If the rent at return cannot be established, the floor is the only defensible answer: it is
     * the one figure that is certainly owed. Guessing high invents a liability, guessing low
     * shortchanges the tenant.
     */
    @Test
    void anUnknowableRentAtReturnFallsBackToWhatWasTaken() {
        var valorization = DepositValorization.compute(
            new BigDecimal("2.00"), new BigDecimal("6000"), BigDecimal.ZERO);

        assertThat(valorization.valorized()).isEqualByComparingTo("6000.00");
        assertThat(valorization.floorApplied()).isTrue();
    }

    @Test
    void aDepositThatWasNeverTakenCannotBeReturned() {
        assertThatThrownBy(() -> DepositValorization.compute(
            new BigDecimal("2.00"), BigDecimal.ZERO, new BigDecimal("3000")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
