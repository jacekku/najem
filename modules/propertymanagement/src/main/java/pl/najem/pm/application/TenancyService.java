package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.Tenancy;

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

    public UUID reserve(UUID unitId, LocalDate startDate, BigDecimal monthlyRent, String paymentReference) {
        UUID tenancyId = UUID.randomUUID();
        store.append(tenancyId, "Tenancy", 0,
            Tenancy.reserve(tenancyId, unitId, startDate, monthlyRent, paymentReference), List.of());
        return tenancyId;
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
