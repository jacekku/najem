package pl.najem.pm.application;

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

    public TenancyService(EventStore store) {
        this.store = store;
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
        var newEvents = tenancy.activate(on);
        store.append(tenancyId, "Tenancy", stream.version(), newEvents,
            List.of(new TenancyActivatedEvent(tenancyId, tenancy.unitId(), tenancy.startDate(),
                tenancy.monthlyRent(), tenancy.paymentReference())));
    }
}
