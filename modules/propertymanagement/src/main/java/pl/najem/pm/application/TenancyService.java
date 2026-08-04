package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contracts.events.RentChangeAppliedEvent;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.contracts.events.TenancyEndedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.ChangeType;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Unit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TenancyService {

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final ProcessDueStore due;

    public TenancyService(EventStore store, JdbcTemplate jdbc, ProcessDueStore due) {
        this.store = store;
        this.jdbc = jdbc;
        this.due = due;
    }

    /**
     * Hard reservation = agreement signed. Registers the period on the Unit's calendar FIRST,
     * at the version the unit was read at: that is what makes the no-overlap invariant safe
     * under concurrency. Two simultaneous reservations on one unit collide on the event store's
     * unique(stream_id, version) and one gets a ConcurrencyException — no read-then-check race.
     * Both appends share this method's transaction, so a failure rolls the period back.
     *
     * @return the new tenancy id and the soft warnings the manager should see
     */
    public Reservation reserve(ReserveTenancy command) {
        var unitStream = store.load(command.unitId(), "Unit");
        var unit = Unit.from(unitStream.events());
        var scoped = withWorkspaceOf(unit, command);

        store.append(command.unitId(), "Unit", unitStream.version(),
            unit.registerTenancyPeriod(scoped.tenancyId(), scoped.startDate(),
                scoped.term().endDate()), List.of());

        var events = Tenancy.reserve(scoped);
        store.append(scoped.tenancyId(), "Tenancy", 0, events, List.of());
        insertProjection(scoped);
        due.arm(TenancyStartProcess.KIND, scoped.tenancyId(), scoped.startDate());
        return new Reservation(scoped.tenancyId(), Tenancy.from(events).warnings().messages());
    }

    /** The workspace is never caller-supplied for a child — it comes from the unit. */
    private static ReserveTenancy withWorkspaceOf(Unit unit, ReserveTenancy command) {
        UUID tenancyId = command.tenancyId() != null ? command.tenancyId() : UUID.randomUUID();
        return new ReserveTenancy(tenancyId, unit.workspaceId(), command.unitId(),
            command.tenantContactIds(), command.guarantorContactIds(), command.startDate(),
            command.term(), command.legalForm(), command.monthly(), command.rentDay(),
            command.depositAmount(), command.paymentReference());
    }

    public void cancelReservation(UUID tenancyId, String reason) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        store.append(tenancyId, "Tenancy", stream.version(), tenancy.cancelReservation(reason), List.of());

        var unitStream = store.load(tenancy.unitId(), "Unit");
        store.append(tenancy.unitId(), "Unit", unitStream.version(),
            Unit.from(unitStream.events()).releaseTenancyPeriod(tenancyId), List.of());
        due.disarm(TenancyStartProcess.KIND, tenancyId);
        jdbc.update("update pm_tenancy set state = 'CANCELLED' where tenancy_id = ?", tenancyId);
    }

    public void addTenant(UUID tenancyId, UUID contactId) {
        var stream = store.load(tenancyId, "Tenancy");
        store.append(tenancyId, "Tenancy", stream.version(),
            Tenancy.from(stream.events()).addTenant(contactId), List.of());
    }

    public void removeTenant(UUID tenancyId, UUID contactId) {
        var stream = store.load(tenancyId, "Tenancy");
        store.append(tenancyId, "Tenancy", stream.version(),
            Tenancy.from(stream.events()).removeTenant(contactId), List.of());
    }

    /**
     * Publishes the contract facts the Tenancy Accounting ACL needs: the agreed monthly total,
     * whether the CONTRACT declares a component split (not inferred from nulls), the legal form
     * that decides the statutory deposit cap, and the deposit itself. All of these are fixed at
     * signing and only PM holds them.
     */
    public void activate(UUID tenancyId, LocalDate on) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        MonthlyAmount monthly = tenancy.monthly();
        MonthlyAmount.Breakdown breakdown = monthly.breakdown();

        store.append(tenancyId, "Tenancy", stream.version(), tenancy.activate(on),
            List.of(new TenancyActivatedEvent(
                tenancy.workspaceId(), tenancyId, tenancy.unitId(), tenancy.startDate(),
                monthly.total(), monthly.componentSplitInContract(),
                breakdown == null ? null : breakdown.rent(),
                breakdown == null ? null : breakdown.adminFee(),
                breakdown == null ? null : breakdown.mediaAdvance(),
                tenancy.legalForm().wireName(), tenancy.depositAmount(),
                tenancy.paymentReference())));
        jdbc.update("update pm_tenancy set state = 'ACTIVE', activated_on = ? where tenancy_id = ?",
            on, tenancyId);
        armEndingSoon(tenancyId);
    }

    public void scheduleRentChange(UUID tenancyId, LocalDate decidedOn, LocalDate effectiveFrom,
                                   MonthlyAmount newMonthly, ChangeType type) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.scheduleRentChange(decidedOn, effectiveFrom, newMonthly, type), List.of());
        // Arm the EARLIEST pending change, not this one: due rows are keyed (kind, subject_id),
        // so scheduling a later change would otherwise overwrite an earlier change's timer and
        // strand it. Fires the day before it takes effect, not at schedule time (§5).
        armNextRentChange(tenancyId);
    }

    public void cancelRentChange(UUID tenancyId, LocalDate effectiveFrom) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.cancelRentChange(effectiveFrom), List.of());
        armNextRentChange(tenancyId);
    }

    private void armNextRentChange(UUID tenancyId) {
        Tenancy.from(store.load(tenancyId, "Tenancy").events()).nextPendingRentChange()
            .ifPresentOrElse(
                next -> due.arm(RentChangeProcess.KIND, tenancyId,
                    next.effectiveFrom().minusDays(1)),
                () -> due.disarm(RentChangeProcess.KIND, tenancyId));
    }

    /**
     * Publishes the new rent WITH its component breakdown. Deposit valorization is computed on
     * the rent component alone, so a flat total would silently corrupt every later valorization
     * (najem-accounting, seq 48).
     */
    public void applyRentChange(UUID tenancyId, LocalDate effectiveFrom) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        var change = tenancy.pendingRentChange(effectiveFrom).orElseThrow(
            () -> new IllegalStateException("No rent change pending for " + effectiveFrom));
        var monthly = change.monthly();
        var breakdown = monthly.breakdown();

        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.applyRentChange(effectiveFrom),
            List.of(new RentChangeAppliedEvent(tenancy.workspaceId(), tenancyId, effectiveFrom,
                monthly.total(),
                breakdown == null ? null : breakdown.rent(),
                breakdown == null ? null : breakdown.adminFee(),
                breakdown == null ? null : breakdown.mediaAdvance(),
                change.type().wireName())));
        jdbc.update("update pm_tenancy set monthly_total = ?, rent = ?, admin_fee = ?, "
                + "media_advance = ?, component_split = ? where tenancy_id = ?",
            monthly.total(),
            breakdown == null ? null : breakdown.rent(),
            breakdown == null ? null : breakdown.adminFee(),
            breakdown == null ? null : breakdown.mediaAdvance(),
            monthly.componentSplitInContract(), tenancyId);
    }

    /**
     * Notice moves the end date, so the ending-soon prompt has to move with it — otherwise the
     * manager is warned a month before a date that is no longer the date.
     */
    public void giveTerminationNotice(UUID tenancyId, String ground, LocalDate noticeDate,
                                      LocalDate effectiveDate, String noticeDocRef) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.giveTerminationNotice(ground, noticeDate, effectiveDate, noticeDocRef),
            List.of());
        armEndingSoon(tenancyId);
    }

    /**
     * Ends the tenancy and everything hanging off it, in one transaction: the unit's calendar is
     * freed, the unit reopens if the manager said so, the settlement clock starts, and every
     * timer still pointed at this tenancy is disarmed. A rent change armed for next month on a
     * tenancy that ended yesterday would otherwise fire against an ended aggregate.
     */
    public void end(UUID tenancyId, EndTenancy command) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());

        store.append(tenancyId, "Tenancy", stream.version(), tenancy.end(command),
            List.of(new TenancyEndedEvent(tenancy.workspaceId(), tenancyId, tenancy.unitId(),
                command.endDate(), command.vacateDate(), command.reason().wireName())));

        // The calendar is freed whatever the manager decided about the market: the tenancy is
        // over, so it must stop blocking a new one. Reopening to rent is the separate decision.
        var unitStream = store.load(tenancy.unitId(), "Unit");
        var unit = Unit.from(unitStream.events());
        var unitEvents = new ArrayList<>(unit.releaseTenancyPeriod(tenancyId));
        // backToMarket is answered either way, never left as whatever the unit happened to be.
        // "Not back to market" has to mean the unit stops being advertised — otherwise a unit
        // listed before the tenancy stays listed after a manager explicitly said it should not.
        unitEvents.addAll(command.backToMarket()
            ? unit.openToRent("tenancy ended")
            : unit.closeToRent("tenancy ended, not returned to market"));
        store.append(tenancy.unitId(), "Unit", unitStream.version(), unitEvents, List.of());

        if (command.vacateDate() != null) {
            due.arm(EndOfTenancyProcess.DEPOSIT_SETTLEMENT_KIND, tenancyId,
                command.vacateDate().plusMonths(1));
        }
        due.disarm(TenancyStartProcess.KIND, tenancyId);
        due.disarm(RentChangeProcess.KIND, tenancyId);
        due.disarm(EndOfTenancyProcess.KIND, tenancyId);
        jdbc.update("update pm_tenancy set state = 'ENDED' where tenancy_id = ?", tenancyId);
    }

    /**
     * One sweep item as its own unit of work — see {@link SweepResult}. Returns false when the
     * timer was armed against a tenancy that has since moved on, which is not a failure.
     */
    public boolean flagEndingSoonIfDue(UUID tenancyId, LocalDate on) {
        var stream = store.load(tenancyId, "Tenancy");
        if (stream.events().isEmpty()) {
            throw new IllegalStateException(
                "Ending-soon timer armed against unknown tenancy " + tenancyId);
        }
        var tenancy = Tenancy.from(stream.events());
        if (tenancy.state() != Tenancy.State.ACTIVE || tenancy.endingSoon()) {
            due.disarm(EndOfTenancyProcess.KIND, tenancyId);
            return false;
        }
        LocalDate end = tenancy.effectiveEndDate();
        if (end == null) {
            due.disarm(EndOfTenancyProcess.KIND, tenancyId);
            return false;
        }
        if (end.minusMonths(1).isAfter(on)) {
            // The end moved later — re-arm rather than warn about a date that changed.
            due.arm(EndOfTenancyProcess.KIND, tenancyId, end.minusMonths(1));
            return false;
        }
        store.append(tenancyId, "Tenancy", stream.version(), tenancy.flagEndingSoon(), List.of());
        due.markFired(EndOfTenancyProcess.KIND, tenancyId);
        return true;
    }

    /** One sweep item as its own unit of work — see {@link SweepResult}. */
    public boolean applyDueRentChange(UUID tenancyId, LocalDate on) {
        var stream = store.load(tenancyId, "Tenancy");
        if (stream.events().isEmpty()) {
            throw new IllegalStateException(
                "Rent-change timer armed against unknown tenancy " + tenancyId);
        }
        var next = Tenancy.from(stream.events()).nextPendingRentChange();
        if (next.isEmpty()) {
            due.disarm(RentChangeProcess.KIND, tenancyId);
            return false;
        }
        var change = next.get();
        if (change.effectiveFrom().minusDays(1).isAfter(on)) {
            // The armed date moved later (the change was rescheduled) — re-arm, don't fire.
            due.arm(RentChangeProcess.KIND, tenancyId, change.effectiveFrom().minusDays(1));
            return false;
        }
        applyRentChange(tenancyId, change.effectiveFrom());

        // A tenancy may have several changes queued; arm the next one rather than
        // leaving it stranded behind a fired timer.
        var later = Tenancy.from(store.load(tenancyId, "Tenancy").events()).nextPendingRentChange();
        if (later.isPresent()) {
            due.arm(RentChangeProcess.KIND, tenancyId, later.get().effectiveFrom().minusDays(1));
        } else {
            due.markFired(RentChangeProcess.KIND, tenancyId);
        }
        return true;
    }

    /** One sweep item as its own unit of work — see {@link SweepResult}. */
    public boolean activateIfDue(UUID tenancyId) {
        var stream = store.load(tenancyId, "Tenancy");
        if (stream.events().isEmpty()) {
            throw new IllegalStateException(
                "Start timer armed against unknown tenancy " + tenancyId);
        }
        var tenancy = Tenancy.from(stream.events());
        if (tenancy.state() != Tenancy.State.RESERVED) {
            due.disarm(TenancyStartProcess.KIND, tenancyId);
            return false;
        }
        if (!tenancy.checklistComplete(ChecklistPhase.PRE_ACTIVATION)
                || !tenancy.autoActivationAllowed()) {
            return false;   // stays armed; the manager is still being prompted
        }
        activate(tenancyId, tenancy.startDate());
        due.markFired(TenancyStartProcess.KIND, tenancyId);
        return true;
    }

    /** Armed at activation, and only when there is an end to warn about. */
    void armEndingSoon(UUID tenancyId) {
        LocalDate end = Tenancy.from(store.load(tenancyId, "Tenancy").events()).effectiveEndDate();
        if (end == null) {
            due.disarm(EndOfTenancyProcess.KIND, tenancyId);
        } else {
            due.arm(EndOfTenancyProcess.KIND, tenancyId, end.minusMonths(1));
        }
    }

    private void insertProjection(ReserveTenancy c) {
        var breakdown = c.monthly().breakdown();
        jdbc.update("insert into pm_tenancy(tenancy_id, workspace_id, unit_id, start_date, end_date, "
                + "legal_form, monthly_total, rent, admin_fee, media_advance, component_split, "
                + "rent_day, deposit_amount, payment_reference, state) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            c.tenancyId(), c.workspaceId(), c.unitId(), c.startDate(), c.term().endDate(),
            c.legalForm().name(), c.monthly().total(),
            breakdown == null ? null : breakdown.rent(),
            breakdown == null ? null : breakdown.adminFee(),
            breakdown == null ? null : breakdown.mediaAdvance(),
            c.monthly().componentSplitInContract(), c.rentDay(), c.depositAmount(),
            c.paymentReference(), Tenancy.State.RESERVED.name());
    }

    /** A reservation and the soft warnings raised against it. */
    public record Reservation(UUID tenancyId, List<String> warnings) {
    }
}
