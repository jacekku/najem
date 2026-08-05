package pl.najem.acc.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.acc.domain.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fast mirror of {@code ArrearsBoardTest}, without a database.
 *
 * <p>Two tiers, and this is the cheap one: it runs in milliseconds, so it is what to run while
 * changing the board. When the two disagree, <strong>the Testcontainers suite is right and this one
 * is wrong</strong> — a fake that has drifted from the statement it stands in for is a bug in the
 * fake, not evidence about the code.
 *
 * <p>What only this tier can show is the wiring: that the service reads through
 * {@link InvoiceRepository} and writes through {@link ArrearsStandingProjection}, and that it
 * writes on every refresh rather than only when the colour has changed.
 */
class ArrearsBoardInMemoryTest {

    private static final UUID WS = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OTHER_WS = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    private static final LocalDate JANUARY = LocalDate.of(2027, 1, 10);
    private static final LocalDate FEBRUARY = LocalDate.of(2027, 2, 10);

    private InMemoryInvoiceRepository invoices;
    private InMemoryArrearsStandingProjection standings;

    @BeforeEach
    void setUp() {
        invoices = new InMemoryInvoiceRepository();
        standings = new InMemoryArrearsStandingProjection();
    }

    @Test
    void aTenancyOwingNothingIsRecordedAsGreen() {
        var tenancyId = UUID.randomUUID();

        boardOn(FEBRUARY).refresh(WS, tenancyId);

        assertThat(standings.find(WS, tenancyId).colour()).isEqualTo(ArrearsColour.GREEN);
    }

    @Test
    void anUnpaidChargePastDueIsRecordedAsRed() {
        var tenancyId = UUID.randomUUID();
        invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("3000"), FEBRUARY);

        boardOn(FEBRUARY.plusDays(1)).refresh(WS, tenancyId);

        assertThat(standings.find(WS, tenancyId).colour()).isEqualTo(ArrearsColour.RED);
        assertThat(standings.find(WS, tenancyId).fullPeriodsInArrears()).isZero();
    }

    @Test
    void rentUnpaidAWholePeriodIsRecordedWithTheStatutoryCounterRunning() {
        var tenancyId = UUID.randomUUID();
        invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("3000"), JANUARY);

        boardOn(FEBRUARY.plusDays(1)).refresh(WS, tenancyId);

        assertThat(standings.find(WS, tenancyId).colour()).isEqualTo(ArrearsColour.BRIGHT_RED);
        assertThat(standings.find(WS, tenancyId).fullPeriodsInArrears()).isOne();
    }

    /** A withdrawn charge is not an obligation, so it stops colouring the board on the next pass. */
    @Test
    void deactivatingTheOnlyArrearTurnsTheBoardGreenAgain() {
        var tenancyId = UUID.randomUUID();
        var chargeId = invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("3000"), JANUARY);
        var board = boardOn(FEBRUARY.plusDays(1));
        board.refresh(WS, tenancyId);

        invoices.deactivate(chargeId);
        board.refresh(WS, tenancyId);

        assertThat(standings.find(WS, tenancyId).colour()).isEqualTo(ArrearsColour.GREEN);
    }

    /** The boundary is the read: another agency's arrears cannot colour this workspace's board. */
    @Test
    void anotherWorkspacesArrearsDoNotColourThisBoard() {
        var tenancyId = UUID.randomUUID();
        invoices.post(OTHER_WS, tenancyId, Component.RENT, new BigDecimal("3000"), JANUARY);

        boardOn(FEBRUARY.plusDays(1)).refresh(WS, tenancyId);

        assertThat(standings.find(WS, tenancyId).colour()).isEqualTo(ArrearsColour.GREEN);
    }

    /**
     * The board is re-derived, not patched: refreshing an unchanged tenancy writes the same answer
     * again rather than deciding it has nothing to do. That is what keeps a stale colour from
     * surviving a change nobody noticed had happened.
     */
    @Test
    void everyRefreshWritesTheStandingEvenWhenNothingHasChanged() {
        var tenancyId = UUID.randomUUID();
        var board = boardOn(FEBRUARY);

        board.refresh(WS, tenancyId);
        board.refresh(WS, tenancyId);

        assertThat(standings.writeCount(WS, tenancyId)).isEqualTo(2);
    }

    private ArrearsBoardService boardOn(LocalDate today) {
        return new ArrearsBoardService(invoices, standings,
            Clock.fixed(today.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()));
    }
}
