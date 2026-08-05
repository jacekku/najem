package pl.najem.acc.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two rules that make money conserved, stated where they now live.
 *
 * <p>An invoice takes from a payment; nothing else moves money. So a payment cannot give what it
 * does not have, and an invoice cannot receive more than it is owed — and both hold for every
 * caller rather than only for the one that remembered to take a minimum.
 */
class SettlementTest {

    private static final LocalDate DUE = LocalDate.of(2027, 1, 10);

    @Test
    void anInvoiceTakesWhatItIsOwedAndTheRestStaysWithThePayment() {
        var payment = new Payment(UUID.randomUUID(), new BigDecimal("2600"));
        var invoice = invoiceOf("2000");

        BigDecimal amount = invoice.applyPayment(payment);

        assertThat(amount).isEqualByComparingTo("2000");
        assertThat(invoice.owed()).isEqualByComparingTo("0");
        assertThat(invoice.isSettled()).isTrue();
        assertThat(payment.remaining()).isEqualByComparingTo("600");
        assertThat(payment.settled()).isEqualByComparingTo("2000");
    }

    /** A charge can never be settled beyond its own amount, however much money is on the table. */
    @Test
    void anInvoiceNeverTakesMoreThanItIsOwed() {
        var payment = new Payment(UUID.randomUUID(), new BigDecimal("10000"));
        var invoice = invoiceOf("2000");

        invoice.applyPayment(payment);
        BigDecimal second = invoice.applyPayment(payment);

        assertThat(second).isEqualByComparingTo("0");
        assertThat(invoice.owed()).isEqualByComparingTo("0");
        assertThat(payment.settled()).isEqualByComparingTo("2000");
    }

    /** A payment cannot give what it does not have; the invoice stays open for the difference. */
    @Test
    void aPaymentGivesOnlyWhatItHasLeft() {
        var payment = new Payment(UUID.randomUUID(), new BigDecimal("500"));
        var invoice = invoiceOf("2000");

        BigDecimal amount = invoice.applyPayment(payment);

        assertThat(amount).isEqualByComparingTo("500");
        assertThat(invoice.owed()).isEqualByComparingTo("1500");
        assertThat(invoice.isSettled()).isFalse();
        assertThat(payment.hasRemaining()).isFalse();
    }

    /** What a payment settles plus what it keeps is exactly what arrived, at every step. */
    @Test
    void whatIsGivenPlusWhatIsKeptIsWhatArrived() {
        var payment = new Payment(UUID.randomUUID(), new BigDecimal("3700"));

        invoiceOf("1500").applyPayment(payment);
        assertThat(payment.settled().add(payment.remaining())).isEqualByComparingTo("3700");

        invoiceOf("1500").applyPayment(payment);
        assertThat(payment.settled().add(payment.remaining())).isEqualByComparingTo("3700");

        invoiceOf("1500").applyPayment(payment);
        assertThat(payment.settled().add(payment.remaining())).isEqualByComparingTo("3700");
    }

    @Test
    void aPaymentThatSettledNothingIsUnmatchedRatherThanPartlySpent() {
        var payment = new Payment(UUID.randomUUID(), new BigDecimal("2000"));

        assertThat(payment.status()).isEqualTo(PaymentStatus.UNMATCHED);
    }

    @Test
    void aPaymentSpentToTheLastGroszIsAllocated() {
        var payment = new Payment(UUID.randomUUID(), new BigDecimal("2000"));

        invoiceOf("2000").applyPayment(payment);

        assertThat(payment.status()).isEqualTo(PaymentStatus.ALLOCATED);
    }

    @Test
    void aPaymentWithCreditLeftOverIsPartiallyAllocated() {
        var payment = new Payment(UUID.randomUUID(), new BigDecimal("2600"));

        invoiceOf("2000").applyPayment(payment);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_ALLOCATED);
    }

    /**
     * The wire names are the status column's own vocabulary, and rows written before this enum
     * existed still carry them. Renaming a constant is free; renaming its wire name is a migration.
     */
    @Test
    void everyStatusTheColumnCarriesReadsBackAsItself() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(PaymentStatus.of(status.wireName())).isEqualTo(status);
        }
        assertThat(PaymentStatus.UNMATCHED.wireName()).isEqualTo("unmatched");
        assertThat(PaymentStatus.SUGGESTED.wireName()).isEqualTo("suggested");
        assertThat(PaymentStatus.PARTIALLY_ALLOCATED.wireName()).isEqualTo("partially-allocated");
        assertThat(PaymentStatus.ALLOCATED.wireName()).isEqualTo("allocated");
        assertThat(PaymentStatus.REVERSED.wireName()).isEqualTo("reversed");
        assertThat(PaymentStatus.NON_TENANT.wireName()).isEqualTo("non-tenant");
    }

    private static Invoice invoiceOf(String owed) {
        return new Invoice(UUID.randomUUID(), Component.RENT, DUE, new BigDecimal(owed));
    }
}
