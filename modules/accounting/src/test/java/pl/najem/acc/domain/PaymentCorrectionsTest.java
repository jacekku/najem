package pl.najem.acc.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which corrections a payment is still open to, asked of the payment.
 *
 * <p>{@code ReversalTest} proves the same refusals through the service and a database. These are
 * the rules themselves, and they hold for every caller rather than for the two that remembered to
 * compare a status to a literal.
 */
class PaymentCorrectionsTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Test
    void anOrdinaryPaymentIsOpenToBothCorrections() {
        var payment = new Payment(ID, new BigDecimal("2000"), PaymentStatus.UNMATCHED);

        assertThat(payment.isReversed()).isFalse();
        assertThatCode(payment::requireReversible).doesNotThrowAnyException();
        assertThatCode(payment::requireAmendable).doesNotThrowAnyException();
    }

    /** Reversing twice would reopen the charges twice and owe the tenant money nobody took. */
    @Test
    void aReversedPaymentCannotBeReversedAgain() {
        var payment = new Payment(ID, BigDecimal.ZERO, PaymentStatus.REVERSED);

        assertThatThrownBy(payment::requireReversible)
            .isInstanceOf(PaymentAlreadyReversedException.class)
            .hasMessageContaining(ID.toString())
            .hasMessageContaining("already reversed");
    }

    /** The opposite failure, and a different sentence: there is nothing left to move. */
    @Test
    void aReversedPaymentCannotBeAmendedOntoAnotherTenancy() {
        var payment = new Payment(ID, BigDecimal.ZERO, PaymentStatus.REVERSED);

        assertThatThrownBy(payment::requireAmendable)
            .isInstanceOf(ReversedPaymentHasNothingToMoveException.class)
            .hasMessageContaining("no money to move");
    }

    /**
     * The two refusals are separate types on purpose. A caller that treats "already reversed" as
     * success — a retried NSF notification is ordinary — must be able to say so without also
     * swallowing the amendment that would have credited a tenancy with recovered funds.
     */
    @Test
    void theTwoRefusalsCanBeCaughtApart() {
        var payment = new Payment(ID, BigDecimal.ZERO, PaymentStatus.REVERSED);

        assertThatThrownBy(payment::requireReversible)
            .isNotInstanceOf(ReversedPaymentHasNothingToMoveException.class);
        assertThatThrownBy(payment::requireAmendable)
            .isNotInstanceOf(PaymentAlreadyReversedException.class);
    }

    /**
     * A payment settled to the last grosz is still correctable — that is the ordinary case for a
     * bounced transfer. Only a reversal closes the door, and it is the recorded status that says
     * so, not the arithmetic of what has been settled.
     */
    @Test
    void beingFullySettledDoesNotCloseTheDoorToACorrection() {
        var payment = new Payment(ID, BigDecimal.ZERO, PaymentStatus.ALLOCATED);

        assertThat(payment.isReversed()).isFalse();
        assertThatCode(payment::requireReversible).doesNotThrowAnyException();
    }

    /**
     * The recorded status and the derived one answer different questions. This payment was written
     * down as reversed and has settled nothing during this allocation, so {@code status()} says
     * unmatched — which is why the guards read the record rather than the arithmetic.
     */
    @Test
    void theRecordedStatusIsNotTheDerivedOne() {
        var payment = new Payment(ID, BigDecimal.ZERO, PaymentStatus.REVERSED);

        assertThat(payment.status()).isEqualTo(PaymentStatus.UNMATCHED);
        assertThat(payment.isReversed()).isTrue();
    }
}
