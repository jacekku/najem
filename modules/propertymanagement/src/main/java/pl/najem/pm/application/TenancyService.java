package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Unit;

import java.time.LocalDate;
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
        var unitStream = store.load(command.unitId());
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
        var stream = store.load(tenancyId);
        var tenancy = Tenancy.from(stream.events());
        store.append(tenancyId, "Tenancy", stream.version(), tenancy.cancelReservation(reason), List.of());

        var unitStream = store.load(tenancy.unitId());
        store.append(tenancy.unitId(), "Unit", unitStream.version(),
            Unit.from(unitStream.events()).releaseTenancyPeriod(tenancyId), List.of());
        due.disarm(TenancyStartProcess.KIND, tenancyId);
        jdbc.update("update pm_tenancy set state = 'CANCELLED' where tenancy_id = ?", tenancyId);
    }

    public void addTenant(UUID tenancyId, UUID contactId) {
        var stream = store.load(tenancyId);
        store.append(tenancyId, "Tenancy", stream.version(),
            Tenancy.from(stream.events()).addTenant(contactId), List.of());
    }

    public void removeTenant(UUID tenancyId, UUID contactId) {
        var stream = store.load(tenancyId);
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
        var stream = store.load(tenancyId);
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
