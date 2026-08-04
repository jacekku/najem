package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenancyEndTest {

    private final UUID tenancyId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private ArrayList<Object> reserved(Term term) {
        return new ArrayList<>(Tenancy.reserve(new ReserveTenancy(tenancyId, workspaceId,
            UUID.randomUUID(), List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 9, 1),
            term, LegalForm.ZWYKLY, new MonthlyAmount(new BigDecimal("2500"), null), 10,
            new BigDecimal("2500"), "NAJEM/A")));
    }

    private ArrayList<Object> active() {
        var history = reserved(new Term.FixedTerm(LocalDate.of(2027, 8, 31)));
        history.addAll(Tenancy.from(history).activate(LocalDate.of(2026, 9, 1)));
        return history;
    }

    @Test
    void withoutANoticeTheEffectiveEndIsTheAgreedTermEnd() {
        assertThat(Tenancy.from(active()).effectiveEndDate()).isEqualTo(LocalDate.of(2027, 8, 31));
    }

    @Test
    void terminationNoticeMovesTheEffectiveEndDateEarlier() {
        var history = active();
        history.addAll(Tenancy.from(history).giveTerminationNotice("tenant notice",
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), "s3://docs/notice.pdf"));

        assertThat(Tenancy.from(history).effectiveEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    /**
     * An indefinite tenancy has no end until somebody gives notice. The process manager keys off
     * this, so null has to mean "nothing to arm" rather than blow up on a perfectly normal tenancy.
     */
    @Test
    void anIndefiniteTenancyHasNoEffectiveEndUntilNoticeIsGiven() {
        var history = reserved(new Term.Indefinite());
        history.addAll(Tenancy.from(history).activate(LocalDate.of(2026, 9, 1)));

        assertThat(Tenancy.from(history).effectiveEndDate()).isNull();

        history.addAll(Tenancy.from(history).giveTerminationNotice("landlord notice",
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 4, 30), null));

        assertThat(Tenancy.from(history).effectiveEndDate()).isEqualTo(LocalDate.of(2027, 4, 30));
    }

    /** A notice can be superseded — the latest one is the one that counts. */
    @Test
    void asecondNoticeReplacesTheFirst() {
        var history = active();
        history.addAll(Tenancy.from(history).giveTerminationNotice("tenant notice",
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), null));
        history.addAll(Tenancy.from(history).giveTerminationNotice("mutually agreed instead",
            LocalDate.of(2026, 11, 1), LocalDate.of(2027, 1, 31), null));

        assertThat(Tenancy.from(history).effectiveEndDate()).isEqualTo(LocalDate.of(2027, 1, 31));
    }

    @Test
    void endingRecordsVacateDateAndReason() {
        var history = active();
        var command = new EndTenancy(LocalDate.of(2027, 8, 31), LocalDate.of(2027, 9, 2),
            EndReason.AGREEMENT_EXPIRY, "keys returned", true);

        var events = Tenancy.from(history).end(command);

        assertThat(events).containsExactly(new TenancyEvents.TenancyEnded(workspaceId, tenancyId,
            LocalDate.of(2027, 8, 31), LocalDate.of(2027, 9, 2), EndReason.AGREEMENT_EXPIRY,
            "keys returned", true));
        history.addAll(events);
        assertThat(Tenancy.from(history).state()).isEqualTo(Tenancy.State.ENDED);
    }

    /**
     * A tenancy activated on the wrong unit is a data-entry mistake, not a tenancy that ran and
     * finished. It still ends — the event store keeps the record — but Reporting reads the reason
     * to keep it out of occupancy statistics, so the distinction has to survive to the event.
     */
    @Test
    void errorAnnulledIsAValidEndingForAMistakenActivation() {
        var history = active();
        var command = new EndTenancy(LocalDate.of(2026, 9, 2), null,
            EndReason.ERROR_ANNULLED, "wrong unit, fat finger", false);

        assertThat(Tenancy.from(history).end(command)).hasSize(1);
    }

    /** The same mistake caught before activation — a reservation can be annulled too. */
    @Test
    void areservedTenancyCanBeAnnulled() {
        var history = reserved(new Term.FixedTerm(LocalDate.of(2027, 8, 31)));

        assertThat(Tenancy.from(history).end(new EndTenancy(LocalDate.of(2026, 9, 1), null,
            EndReason.ERROR_ANNULLED, "never happened", false))).hasSize(1);
    }

    @Test
    void endingAnEndedTenancyIsRejected() {
        var history = active();
        history.addAll(Tenancy.from(history).end(new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 9, 2), EndReason.AGREEMENT_EXPIRY, "", true)));

        assertThatThrownBy(() -> Tenancy.from(history).end(new EndTenancy(LocalDate.of(2027, 9, 30),
                null, EndReason.MUTUAL_AGREEMENT, "", true)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void endingACancelledTenancyIsRejected() {
        var history = reserved(new Term.FixedTerm(LocalDate.of(2027, 8, 31)));
        history.addAll(Tenancy.from(history).cancelReservation("tenant withdrew"));

        assertThatThrownBy(() -> Tenancy.from(history).end(new EndTenancy(LocalDate.of(2027, 8, 31),
                null, EndReason.MUTUAL_AGREEMENT, "", true)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noticeOnAnEndedTenancyIsRejected() {
        var history = active();
        history.addAll(Tenancy.from(history).end(new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 9, 2), EndReason.AGREEMENT_EXPIRY, "", true)));

        assertThatThrownBy(() -> Tenancy.from(history).giveTerminationNotice("too late",
                LocalDate.of(2027, 10, 1), LocalDate.of(2027, 12, 31), null))
            .isInstanceOf(IllegalStateException.class);
    }

    /** Accounting reads reasonType off the wire as a string; the enum name is not the wire name. */
    @Test
    void reasonsHaveStableHyphenatedWireNames() {
        assertThat(EndReason.AGREEMENT_EXPIRY.wireName()).isEqualTo("agreement-expiry");
        assertThat(EndReason.ERROR_ANNULLED.wireName()).isEqualTo("error-annulled");
        assertThat(EndReason.RENT_INCREASE_REFUSAL.wireName()).isEqualTo("rent-increase-refusal");
    }
}
