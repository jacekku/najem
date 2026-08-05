package pl.najem.acc.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.acc.domain.ChargeDeactivated;
import pl.najem.acc.domain.ChargePosted;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.CreditNoteIssued;
import pl.najem.acc.domain.WarningKind;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fast mirror of {@code InvoiceLifecycleTest}, without a database.
 *
 * <p>Two tiers, and this is the cheap one. When they disagree, <strong>the Testcontainers suite is
 * right and this one is wrong</strong>.
 *
 * <p>This tier could not exist until {@code WarningService} went behind a port: the service takes
 * one, and a concrete one needed a database, so every question about posting a charge cost a
 * container. That is what an unextracted collaborator costs — not a layering violation on a
 * diagram, but a whole tier of tests nobody could write.
 */
class InvoiceLifecycleInMemoryTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate JANUARY = LocalDate.of(2027, 1, 10);

    private InMemoryInvoiceRepository invoices;
    private InMemoryWarningRepository warnings;
    private InMemoryArrearsStandingProjection standings;
    private RecordingEventStore store;
    private InvoiceService service;

    @BeforeEach
    void setUp() {
        invoices = new InMemoryInvoiceRepository();
        warnings = new InMemoryWarningRepository();
        standings = new InMemoryArrearsStandingProjection();
        store = new RecordingEventStore();
        var board = new ArrearsBoardService(invoices, standings,
            Clock.fixed(JANUARY.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()));
        service = new InvoiceService(store, invoices, new WarningService(warnings), board);
    }

    @Test
    void postingAMonthWritesOneRowAndOneEventPerContractualLine() {
        var tenancyId = UUID.randomUUID();

        var posted = service.postMonth(WS, tenancyId,
            MonthlyBreakdown.split(new BigDecimal("3000"), new BigDecimal("2400"),
                new BigDecimal("300"), new BigDecimal("300")),
            JANUARY, "NAJEM/1/2027");

        assertThat(posted.invoiceIds()).hasSize(3);
        assertThat(store.appended()).hasSize(3).allMatch(event -> event instanceof ChargePosted);
        assertThat(invoices.openInvoices(WS, tenancyId)).hasSize(3);
    }

    /**
     * The collapse rule is a warning, not a refusal — the posting stands and the manager is told.
     * That the flag reaches the register at all is what only this wiring can show.
     */
    @Test
    void aContractWithoutASplitIsPostedAndFlagged() {
        var tenancyId = UUID.randomUUID();

        service.postMonth(WS, tenancyId, MonthlyBreakdown.unsplit(new BigDecimal("3000")),
            JANUARY, "NAJEM/2/2027");

        assertThat(invoices.openInvoices(WS, tenancyId)).hasSize(1);
        assertThat(warnings.unseen(WS)).singleElement()
            .satisfies(warning -> assertThat(warning.kind()).isEqualTo(WarningKind.COLLAPSE_RULE));
    }

    /** A warning read back out of the register carries the tenancy it was raised against. */
    @Test
    void aRaisedWarningNamesItsTenancy() {
        var tenancyId = UUID.randomUUID();

        service.postMonth(WS, tenancyId, MonthlyBreakdown.unsplit(new BigDecimal("3000")),
            JANUARY, "NAJEM/3/2027");

        assertThat(warnings.unseen(WS)).singleElement()
            .satisfies(warning -> assertThat(warning.tenancyId()).isEqualTo(tenancyId));
    }

    /** Posting one component on its own raises nothing: there is no breakdown to disagree with. */
    @Test
    void postingASingleComponentFlagsNothing() {
        service.post(WS, UUID.randomUUID(), Component.DEPOSIT, new BigDecimal("6000"), JANUARY,
            "KAUCJA/1/2027");

        assertThat(warnings.unseen(WS)).isEmpty();
    }

    @Test
    void withdrawingAnUnpaidInvoiceLeavesItOnFileAndOffTheBoard() {
        var tenancyId = UUID.randomUUID();
        var invoiceId = service.postRent(WS, tenancyId, new BigDecimal("3000"), JANUARY,
            "NAJEM/4/2027");

        service.withdraw(WS, invoiceId, "billed in error");

        assertThat(invoices.find(invoiceId).active()).isFalse();
        assertThat(store.appended()).anyMatch(event -> event instanceof ChargeDeactivated);
        assertThat(standings.find(WS, tenancyId).colour()).isEqualTo(ArrearsColour.GREEN);
    }

    /** The two corrections are not interchangeable, in either direction. */
    @Test
    void aPaidInvoiceIsCreditedRatherThanWithdrawn() {
        var tenancyId = UUID.randomUUID();
        var invoiceId = service.postRent(WS, tenancyId, new BigDecimal("3000"), JANUARY,
            "NAJEM/5/2027");
        invoices.applyAllocation(WS, invoiceId, new BigDecimal("3000"));

        assertThatThrownBy(() -> service.withdraw(WS, invoiceId, "billed in error"))
            .isInstanceOf(InvoiceAlreadyPaidException.class);

        var creditNoteId = service.issueCreditNote(WS, invoiceId, new BigDecimal("100"), "goodwill");

        assertThat(creditNoteId).isNotNull();
        assertThat(invoices.creditNotes()).singleElement()
            .satisfies(note -> assertThat(note.chargeId()).isEqualTo(invoiceId));
        assertThat(store.appended()).anyMatch(event -> event instanceof CreditNoteIssued);
    }

    @Test
    void anUnpaidInvoiceIsWithdrawnRatherThanCredited() {
        var invoiceId = service.postRent(WS, UUID.randomUUID(), new BigDecimal("3000"), JANUARY,
            "NAJEM/6/2027");

        assertThatThrownBy(() -> service.issueCreditNote(WS, invoiceId, new BigDecimal("100"), "x"))
            .isInstanceOf(InvoiceNotPaidException.class);
    }

    /** A credit note larger than the charge would put the tenant's documents at odds with the books. */
    @Test
    void aCreditNoteMayNotExceedWhatWasCharged() {
        var invoiceId = service.postRent(WS, UUID.randomUUID(), new BigDecimal("3000"), JANUARY,
            "NAJEM/7/2027");
        invoices.applyAllocation(WS, invoiceId, new BigDecimal("3000"));

        assertThatThrownBy(
            () -> service.issueCreditNote(WS, invoiceId, new BigDecimal("3000.01"), "too much"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The boundary is the read: another agency's invoice is absent, not forbidden. */
    @Test
    void anInvoiceInAnotherWorkspaceCannotBeCorrected() {
        var invoiceId = service.postRent(WS, UUID.randomUUID(), new BigDecimal("3000"), JANUARY,
            "NAJEM/8/2027");
        var intruder = UUID.fromString("00000000-0000-0000-0000-0000000000af");

        assertThatThrownBy(() -> service.withdraw(intruder, invoiceId, "not mine"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
