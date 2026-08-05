package pl.najem.acc.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.DepositCharged;
import pl.najem.acc.domain.DepositSettled;
import pl.najem.acc.domain.WarningKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fast mirror of {@code DepositTest} and {@code DepositSettlementTest}.
 *
 * <p>Two tiers, and this is the cheap one. When they disagree, <strong>the Testcontainers suite is
 * right and this one is wrong</strong>.
 *
 * <p>What only this tier shows is the wiring: that the deposit's own obligation is written through
 * {@link InvoiceRepository} like any other charge, that deductions settle real invoices, and that
 * the deposit charge announces itself with {@code DepositCharged} and nothing else.
 */
class DepositInMemoryTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate START = LocalDate.of(2027, 1, 10);
    private static final LocalDate END = LocalDate.of(2028, 1, 10);

    private InMemoryInvoiceRepository invoices;
    private InMemoryDepositRepository depositRows;
    private InMemoryWarningRepository warnings;
    private RecordingEventStore store;
    private DepositService deposits;

    @BeforeEach
    void setUp() {
        invoices = new InMemoryInvoiceRepository();
        depositRows = new InMemoryDepositRepository(invoices);
        warnings = new InMemoryWarningRepository();
        store = new RecordingEventStore();
        deposits = new DepositService(store, depositRows, invoices, new WarningService(warnings));
    }

    /**
     * A deposit is an ordinary obligation with a deposit record beside it — but it announces itself
     * as DepositCharged, not ChargePosted. Posting it through InvoiceService would append a second
     * event for the same fact and refresh the board over a charge that is not yet an arrear.
     */
    @Test
    void chargingADepositWritesOneObligationAndOneDepositEvent() {
        var tenancyId = UUID.randomUUID();

        var depositId = deposits.chargeOnActivation(WS, tenancyId, new BigDecimal("6000"),
            new BigDecimal("3000"), "okazjonalny", START, "KAUCJA/1/2027");

        assertThat(depositId).isPresent();
        assertThat(invoices.openInvoices(WS, tenancyId)).singleElement()
            .satisfies(invoice -> assertThat(invoice.component()).isEqualTo(Component.DEPOSIT));
        assertThat(store.appended()).singleElement().isInstanceOf(DepositCharged.class);
        assertThat(warnings.unseen(WS)).isEmpty();
    }

    /**
     * A term, not a deposit of nothing. A zero-value charge would tell every later reader that a
     * deposit exists and has been settled.
     */
    @Test
    void aContractWithoutADepositPostsNothingAtAll() {
        var tenancyId = UUID.randomUUID();

        assertThat(deposits.chargeOnActivation(WS, tenancyId, null, new BigDecimal("3000"),
            "okazjonalny", START, "KAUCJA/2/2027")).isEmpty();
        assertThat(deposits.chargeOnActivation(WS, tenancyId, BigDecimal.ZERO,
            new BigDecimal("3000"), "okazjonalny", START, "KAUCJA/2/2027")).isEmpty();

        assertThat(invoices.openInvoices(WS, tenancyId)).isEmpty();
        assertThat(store.appended()).isEmpty();
    }

    /** The cap warns and never refuses: the deposit is recorded, and the manager is told. */
    @Test
    void aDepositOverTheCapIsRecordedAndFlagged() {
        var tenancyId = UUID.randomUUID();

        deposits.chargeOnActivation(WS, tenancyId, new BigDecimal("21000"),
            new BigDecimal("3000"), "okazjonalny", START, "KAUCJA/3/2027");

        assertThat(invoices.openInvoices(WS, tenancyId)).hasSize(1);
        assertThat(warnings.unseen(WS)).singleElement().satisfies(warning -> {
            assertThat(warning.kind()).isEqualTo(WarningKind.DEPOSIT_CAP_EXCEEDED);
            assertThat(warning.detail()).contains("21000", "7.00", "OKAZJONALNY", "6-krotność");
        });
    }

    @Test
    void aDepositAgainstNoRentIsRecordedAndSaidToBeUnchecked() {
        deposits.chargeOnActivation(WS, UUID.randomUUID(), new BigDecimal("6000"), BigDecimal.ZERO,
            "okazjonalny", START, "KAUCJA/4/2027");

        assertThat(warnings.unseen(WS)).singleElement().satisfies(warning ->
            assertThat(warning.kind()).isEqualTo(WarningKind.DEPOSIT_CAP_UNCHECKABLE));
    }

    /** Art. 6 ust. 4: valorized to the agreed multiple of the czynsz in force on the day of return. */
    @Test
    void aDepositWithNoArrearsIsReturnedValorized() {
        var tenancyId = UUID.randomUUID();
        var depositId = chargedAndPaid(tenancyId, "6000", "3000");

        var returned = deposits.settle(WS, tenancyId, new BigDecimal("3500"), END);

        assertThat(returned).isEqualByComparingTo("7000");
        assertThat(depositRows.settlementOf(depositId).deducted()).isEqualByComparingTo("0");
        assertThat(store.appended()).last().isInstanceOf(DepositSettled.class);
    }

    /**
     * Deductions settle the invoices they pay, oldest first, and are itemised against them — a
     * disputed deduction has to be traceable to the obligation it covered.
     */
    @Test
    void arrearsAreDeductedOldestFirstAndSettleTheInvoicesTheyPay() {
        var tenancyId = UUID.randomUUID();
        var depositId = chargedAndPaid(tenancyId, "6000", "3000");
        var january = invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("3000"), START);
        var february = invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("3000"),
            START.plusMonths(1));

        var returned = deposits.settle(WS, tenancyId, new BigDecimal("3000"), END);

        assertThat(returned).isEqualByComparingTo("0");
        assertThat(invoices.settled(january)).isEqualByComparingTo("3000");
        assertThat(invoices.settled(february)).isEqualByComparingTo("3000");
        assertThat(depositRows.deductions()).extracting(InMemoryDepositRepository.Deduction::invoiceId)
            .containsExactly(january, february);
        assertThat(depositRows.deductions()).allMatch(d -> d.depositId().equals(depositId));
    }

    /** What the deposit could not cover stays owed. Settling it is not a forgiveness. */
    @Test
    void arrearsBeyondTheDepositRemainOwed() {
        var tenancyId = UUID.randomUUID();
        chargedAndPaid(tenancyId, "3000", "3000");
        invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("9000"), START);

        var returned = deposits.settle(WS, tenancyId, new BigDecimal("3000"), END);

        assertThat(returned).isEqualByComparingTo("0");
        assertThat(invoices.openInvoices(WS, tenancyId)).singleElement()
            .satisfies(invoice -> assertThat(invoice.owed()).isEqualByComparingTo("6000"));
    }

    /** The deposit's own charge is not one of its own arrears; it must not deduct itself. */
    @Test
    void theDepositChargeIsNotDeductedFromItself() {
        var tenancyId = UUID.randomUUID();
        chargedAndPaid(tenancyId, "6000", "3000");

        deposits.settle(WS, tenancyId, new BigDecimal("3000"), END);

        assertThat(depositRows.deductions()).isEmpty();
    }

    /** Charged but never paid is not money in hand, and valorizing it would invent funds. */
    @Test
    void anUnpaidDepositCannotBeReturned() {
        var tenancyId = UUID.randomUUID();
        deposits.chargeOnActivation(WS, tenancyId, new BigDecimal("6000"), new BigDecimal("3000"),
            "okazjonalny", START, "KAUCJA/5/2027");

        assertThatThrownBy(() -> deposits.settle(WS, tenancyId, new BigDecimal("3000"), END))
            .isInstanceOf(DepositNotHeldException.class)
            .hasMessageContaining("never paid");
    }

    @Test
    void aDepositAlreadyReturnedIsNotReturnedAgain() {
        var tenancyId = UUID.randomUUID();
        chargedAndPaid(tenancyId, "6000", "3000");
        deposits.settle(WS, tenancyId, new BigDecimal("3000"), END);

        assertThatThrownBy(() -> deposits.settle(WS, tenancyId, new BigDecimal("3000"), END))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("pay the tenant twice");
    }

    @Test
    void aTenancyWithNoDepositHasNothingToReturn() {
        assertThatThrownBy(
            () -> deposits.settle(WS, UUID.randomUUID(), new BigDecimal("3000"), END))
            .isInstanceOf(DepositNotHeldException.class);
    }

    /** Charges the deposit and has the tenant pay it, which is what makes it returnable. */
    private UUID chargedAndPaid(UUID tenancyId, String amount, String rent) {
        var depositId = deposits.chargeOnActivation(WS, tenancyId, new BigDecimal(amount),
            new BigDecimal(rent), "okazjonalny", START, "KAUCJA/" + tenancyId).orElseThrow();
        var invoice = invoices.openInvoices(WS, tenancyId).stream()
            .filter(open -> open.component() == Component.DEPOSIT)
            .findFirst().orElseThrow();
        invoices.applyAllocation(WS, invoice.invoiceId(), new BigDecimal(amount));
        return depositId;
    }
}
