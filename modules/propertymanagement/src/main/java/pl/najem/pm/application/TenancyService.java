package pl.najem.pm.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contracts.events.RentChangeAppliedEvent;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.contracts.events.TenancyEndedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.ChangeType;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.DocType;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Unit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tenancy lifecycle, from a signed agreement to a settled end.
 *
 * <p><b>Two kinds of entry point, and the difference is who is acting.</b> A manager's command takes
 * the workspace of the caller making it and checks it against the aggregate before changing
 * anything. The sweep methods — {@code activateIfDue}, {@code applyDueRentChange},
 * {@code flagEndingSoonIfDue} — take no workspace, because there is no caller: the system is acting
 * on a timer it armed itself, and inventing a workspace to check against would be theatre. They are
 * reachable only from the process managers, which are driven by the scheduler.
 *
 * <p>The check used to be split in two. The controller asked {@code WorkspaceGuard} whether a row
 * with this tenancy id existed in the caller's workspace; the service then rebuilt the aggregate
 * from its stream and read the same workspace off it again. Two answers to one question, from two
 * stores that a second statement is allowed to leave disagreeing — and pm_tenancy is the derived
 * one. {@code Tenancy.requireOwnedBy} asks the record, once, next to the decision, and covers every
 * route in rather than the one that goes through a controller.
 *
 * <p>Its store is reached through {@link TenancyProjection}, which is write-only on purpose: nothing
 * here may read a projection row back to decide something.
 */
@Service
@Transactional
public class TenancyService {

    private final EventStore store;
    private final TenancyProjection projection;
    private final ProcessDueRepository due;

    public TenancyService(EventStore store, TenancyProjection projection, ProcessDueRepository due) {
        this.store = store;
        this.projection = projection;
        this.due = due;
    }

    /**
     * Hard reservation = agreement signed. Registers the period on the Unit's calendar FIRST,
     * at the version the unit was read at: that is what makes the no-overlap invariant safe
     * under concurrency. Two simultaneous reservations on one unit collide on the event store's
     * unique(stream_id, stream_type, version) and one gets a ConcurrencyException — no
     * read-then-check race. Both writers are (unitId, "Unit"), same id AND same type, so stream
     * identity moving into the constraint (V10) leaves this guarantee exactly as it was.
     * Both appends share this method's transaction, so a failure rolls the period back.
     *
     * <p>The unit is loaded once and does both jobs: it refuses a caller who does not own it, and
     * it supplies the workspace the new tenancy is stamped with.
     *
     * @return the new tenancy id and the soft warnings the manager should see
     */
    public Reservation reserve(UUID workspaceId, ReserveTenancy command) {
        var unitStream = store.load(command.unitId(), "Unit");
        var unit = Unit.from(unitStream.events());
        unit.requireOwnedBy(workspaceId);
        var scoped = withWorkspaceOf(unit, command);

        store.append(command.unitId(), "Unit", unitStream.version(),
            unit.registerTenancyPeriod(scoped.tenancyId(), scoped.startDate(),
                scoped.term().endDate()), List.of());

        var events = Tenancy.reserve(scoped);
        store.append(scoped.tenancyId(), "Tenancy", 0, events, List.of());
        projection.tenancyReserved(scoped);
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

    public void cancelReservation(UUID workspaceId, UUID tenancyId, String reason) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(), tenancy.cancelReservation(reason), List.of());

        var unitStream = store.load(tenancy.unitId(), "Unit");
        store.append(tenancy.unitId(), "Unit", unitStream.version(),
            Unit.from(unitStream.events()).releaseTenancyPeriod(tenancyId), List.of());
        due.disarm(TenancyStartProcess.KIND, tenancyId);
        projection.reservationCancelled(tenancyId, tenancy.workspaceId());
    }

    public void addTenant(UUID workspaceId, UUID tenancyId, UUID contactId) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(), tenancy.addTenant(contactId), List.of());
    }

    public void removeTenant(UUID workspaceId, UUID tenancyId, UUID contactId) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(), tenancy.removeTenant(contactId), List.of());
    }

    /** A manager activating early, ahead of the timer armed at reservation. */
    public List<String> activate(UUID workspaceId, UUID tenancyId, LocalDate on) {
        Tenancy.from(store.load(tenancyId, "Tenancy").events()).requireOwnedBy(workspaceId);
        return activateNow(tenancyId, on);
    }

    /**
     * Publishes the contract facts the Tenancy Accounting ACL needs: the agreed monthly total,
     * whether the CONTRACT declares a component split (not inferred from nulls), the legal form
     * that decides the statutory deposit cap, and the deposit itself. All of these are fixed at
     * signing and only PM holds them.
     *
     * <p>Shared with {@link #activateIfDue}, which has no caller to check — see the class javadoc.
     */
    private List<String> activateNow(UUID tenancyId, LocalDate on) {
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
        projection.activated(tenancyId, tenancy.workspaceId(), on);
        armEndingSoon(tenancyId);
        return warningsOf(tenancyId);
    }

    /**
     * Returns the warnings the scheduled change raised — a unilateral increase under three
     * months' notice is exactly the statutory flag a manager must see at the moment they set it,
     * not on a report later. Swallowing it here would make the expert-system stance decorative.
     */
    public List<String> scheduleRentChange(UUID workspaceId, UUID tenancyId, LocalDate decidedOn,
                                           LocalDate effectiveFrom, MonthlyAmount newMonthly,
                                           ChangeType type) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.scheduleRentChange(decidedOn, effectiveFrom, newMonthly, type), List.of());
        // Arm the EARLIEST pending change, not this one: due rows are keyed (kind, subject_id),
        // so scheduling a later change would otherwise overwrite an earlier change's timer and
        // strand it. Fires the day before it takes effect, not at schedule time (§5).
        armNextRentChange(tenancyId);
        return warningsOf(tenancyId);
    }

    public void cancelRentChange(UUID workspaceId, UUID tenancyId, LocalDate effectiveFrom) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
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
     *
     * <p>No workspace argument: the only caller is {@link #applyDueRentChange}, a timer firing.
     */
    void applyRentChange(UUID tenancyId, LocalDate effectiveFrom) {
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
        projection.rentChanged(tenancyId, tenancy.workspaceId(), monthly);
    }

    /**
     * Notice moves the end date, so the ending-soon prompt has to move with it — otherwise the
     * manager is warned a month before a date that is no longer the date.
     */
    public void giveTerminationNotice(UUID workspaceId, UUID tenancyId, String ground,
                                      LocalDate noticeDate, LocalDate effectiveDate,
                                      String noticeDocRef) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
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
    public void end(UUID workspaceId, UUID tenancyId, EndTenancy command) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);

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
        projection.ended(tenancyId, tenancy.workspaceId());
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

    /** The soft checks on one tenancy, computed live — see AttentionController. */
    public List<String> warnings(UUID workspaceId, UUID tenancyId) {
        var tenancy = Tenancy.from(store.load(tenancyId, "Tenancy").events());
        tenancy.requireOwnedBy(workspaceId);
        return tenancy.warnings().messages();
    }

    /** The same read with the ownership question already settled by the caller above. */
    private List<String> warningsOf(UUID tenancyId) {
        return Tenancy.from(store.load(tenancyId, "Tenancy").events()).warnings().messages();
    }

    public void addComment(UUID workspaceId, UUID tenancyId, String text) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(), tenancy.addComment(text), List.of());
    }

    /**
     * Returns the warnings the correction raised, because the one that matters — correcting a
     * fact Accounting already holds — is only useful if it reaches the manager who made it.
     */
    public List<String> correctDetails(UUID workspaceId, UUID tenancyId,
                                       Map<String, String> corrections) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.correctDetails(corrections), List.of());

        var corrected = Tenancy.from(store.load(tenancyId, "Tenancy").events());
        projection.detailsCorrected(tenancyId, corrected.workspaceId(),
            corrected.paymentReference(), corrected.rentDay(), corrected.startDate(),
            corrected.monthly().total());
        return corrected.warnings().messages();
    }

    public void attachDocument(UUID workspaceId, UUID tenancyId, DocType type, String s3Ref,
                               LocalDate validFrom, LocalDate validTo, LocalDate date) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.attachDocument(type, s3Ref, validFrom, validTo, date), List.of());
        // The aggregate decides which policy is current (a renewal supersedes), so the
        // projection copies its answer rather than reimplementing "latest".
        var updated = Tenancy.from(store.load(tenancyId, "Tenancy").events());
        updated.insuranceExpiry().ifPresent(expiry ->
            projection.insuranceExpirySet(tenancyId, updated.workspaceId(), expiry));
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
        activateNow(tenancyId, tenancy.startDate());
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

    /** A reservation and the soft warnings raised against it. */
    public record Reservation(UUID tenancyId, List<String> warnings) {
    }
}
