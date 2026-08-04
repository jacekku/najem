package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Unit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TenancyService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public TenancyService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    /**
     * Hard reservation = agreement signed. Registers the period on the Unit's calendar FIRST,
     * at the version the unit was read at: that is what makes the no-overlap invariant safe
     * under concurrency. Two simultaneous reservations on one unit collide on the event store's
     * unique(stream_id, version) and one gets a ConcurrencyException — no read-then-check race.
     * Both appends share this method's transaction, so a failure rolls the period back.
     */
    public UUID reserve(UUID unitId, LocalDate startDate, LocalDate endDate,
                        BigDecimal monthlyRent, String paymentReference) {
        UUID tenancyId = UUID.randomUUID();
        var unitStream = store.load(unitId);
        var unit = Unit.from(unitStream.events());

        store.append(unitId, "Unit", unitStream.version(),
            unit.registerTenancyPeriod(tenancyId, startDate, endDate), List.of());
        store.append(tenancyId, "Tenancy", 0,
            Tenancy.reserve(tenancyId, unit.workspaceId(), unitId, startDate, endDate,
                monthlyRent, paymentReference), List.of());
        return tenancyId;
    }

    /** Reservation fell through — the unit's slot is freed (domain model §2 item 13). */
    public void cancelReservation(UUID tenancyId, String reason) {
        var stream = store.load(tenancyId);
        var tenancy = Tenancy.from(stream.events());
        store.append(tenancyId, "Tenancy", stream.version(), tenancy.cancelReservation(reason), List.of());

        var unitStream = store.load(tenancy.unitId());
        store.append(tenancy.unitId(), "Unit", unitStream.version(),
            Unit.from(unitStream.events()).releaseTenancyPeriod(tenancyId), List.of());
    }

    public void activate(UUID tenancyId, LocalDate on) {
        var stream = store.load(tenancyId);
        var tenancy = Tenancy.from(stream.events());
        // The workspace comes from the unit the tenancy sits on, never from a constant.
        UUID workspaceId = jdbc.queryForObject(
            "select workspace_id from pm_unit where unit_id = ?", UUID.class, tenancy.unitId());
        // v2 bridge values until Task 4 (full reservation) supplies the real contract facts:
        // no contractual split yet (collapse rule: whole amount is rent), portfolio default
        // legalForm "zwykly", no deposit known.
        store.append(tenancyId, "Tenancy", stream.version(), tenancy.activate(on),
            List.of(new TenancyActivatedEvent(workspaceId, tenancyId, tenancy.unitId(),
                tenancy.startDate(), tenancy.monthlyRent(), false, null, null, null,
                "zwykly", null, tenancy.paymentReference())));
    }
}
