package pl.najem.acc.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.SuspenseAge;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ageing bands and what leaves the queue, without a database.
 *
 * <p>{@code SuspenseTest} is the authority. What is asked here is the part that stopped being SQL:
 * how long something has waited is arithmetic, but what that means is a policy with two thresholds,
 * and a boundary deserves to be tested <em>at</em> the boundary rather than near it.
 */
class SuspenseInMemoryTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate BOOKED = LocalDate.of(2027, 4, 1);

    private InMemoryPaymentRepository payments;
    private RecordingEventStore store;
    private SuspenseService suspense;

    @BeforeEach
    void setUp() {
        payments = new InMemoryPaymentRepository();
        store = new RecordingEventStore();
        var allocation = new AllocationService(store, payments, new InMemoryInvoiceRepository(),
            new InMemoryAccountingRepository());
        var board = new ArrearsBoardService(new InMemoryInvoiceRepository(),
            new InMemoryArrearsStandingProjection(), Clock.systemDefaultZone());
        suspense = new SuspenseService(store, payments,
            new AccountingService(allocation, board), Clock.systemDefaultZone(), 7, 30);
    }

    /** Money that has arrived and settled nothing is what the queue is for. */
    @Test
    void aPaymentHoldingUnplacedMoneyIsWaiting() {
        var paymentId = arrived("2000", "NAJEM/?/2027");

        assertThat(suspense.waiting(WS, BOOKED)).singleElement().satisfies(entry -> {
            assertThat(entry.paymentId()).isEqualTo(paymentId);
            assertThat(entry.amount()).isEqualByComparingTo("2000");
            assertThat(entry.title()).isEqualTo("NAJEM/?/2027");
        });
    }

    /**
     * The two boundaries, exactly. Under the threshold is the calmer band and on it is the louder
     * one — a band that changed a day late would let a month's decision slip past unremarked.
     */
    @Test
    void theAgeingBandsChangeOnTheirThresholdDayAndNotBefore() {
        arrived("2000", "NAJEM/?/2027");

        assertThat(ageOn(BOOKED)).isEqualTo(SuspenseAge.FRESH);
        assertThat(ageOn(BOOKED.plusDays(6))).isEqualTo(SuspenseAge.FRESH);
        assertThat(ageOn(BOOKED.plusDays(7))).isEqualTo(SuspenseAge.WARN);
        assertThat(ageOn(BOOKED.plusDays(29))).isEqualTo(SuspenseAge.WARN);
        assertThat(ageOn(BOOKED.plusDays(30))).isEqualTo(SuspenseAge.RED);
    }

    @Test
    void howLongItHasWaitedIsCountedFromTheBookingDate() {
        arrived("2000", "NAJEM/?/2027");

        assertThat(suspense.waiting(WS, BOOKED.plusDays(12)).getFirst().daysWaiting()).isEqualTo(12);
    }

    /** Classified money is still on the books as a bank fact, and out of the queue. */
    @Test
    void aNonTenantLineLeavesTheQueueWithoutLeavingTheBooks() {
        var paymentId = arrived("2000", "OPŁATA ZA PROWADZENIE RACHUNKU");

        suspense.markNonTenant(WS, paymentId, "bank fee");

        assertThat(suspense.waiting(WS, BOOKED)).isEmpty();
        assertThat(payments.find(WS, paymentId).unallocatedAmount()).isEqualByComparingTo("2000");
    }

    /** A judgement about money that cannot say what it was is one nobody can review later. */
    @Test
    void classifyingNeedsAReason() {
        var paymentId = arrived("2000", "?");

        for (String nothing : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> suspense.markNonTenant(WS, paymentId, nothing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a reason");
        }
        assertThat(suspense.waiting(WS, BOOKED)).hasSize(1);
    }

    /** Another agency's payment is absent, not forbidden — and must not be judged from here. */
    @Test
    void aPaymentInAnotherWorkspaceCannotBeClassified() {
        var paymentId = arrived("2000", "?");
        var intruder = UUID.fromString("00000000-0000-0000-0000-0000000000af");

        assertThatThrownBy(() -> suspense.markNonTenant(intruder, paymentId, "not mine to judge"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(suspense.waiting(WS, BOOKED)).hasSize(1);
    }

    /** Oldest first, so a queue a human works down does not reshuffle between two readings. */
    @Test
    void theQueueIsOrderedOldestBookingFirst() {
        var later = UUID.randomUUID();
        payments.arrived(WS, later, new BigDecimal("500"), BOOKED.plusDays(3), "later");
        var earlier = UUID.randomUUID();
        payments.arrived(WS, earlier, new BigDecimal("500"), BOOKED, "earlier");

        assertThat(suspense.waiting(WS, BOOKED.plusDays(10)))
            .extracting(SuspenseEntry::paymentId)
            .containsExactly(earlier, later);
    }

    private SuspenseAge ageOn(LocalDate asOf) {
        return suspense.waiting(WS, asOf).getFirst().age();
    }

    private UUID arrived(String amount, String title) {
        UUID paymentId = UUID.randomUUID();
        payments.arrived(WS, paymentId, new BigDecimal(amount), BOOKED, title);
        return paymentId;
    }
}
