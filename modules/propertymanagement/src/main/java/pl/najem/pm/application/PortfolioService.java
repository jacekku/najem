package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.PropertyCreated;
import pl.najem.pm.domain.UnitAdded;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PortfolioService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public PortfolioService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID createProperty(String address) {
        UUID propertyId = UUID.randomUUID();
        store.append(propertyId, "Property", 0, List.of(new PropertyCreated(propertyId, address)), List.of());
        return propertyId;
    }

    public UUID addUnit(UUID propertyId, String name, BigDecimal baseRent) {
        UUID unitId = UUID.randomUUID();
        store.append(unitId, "Unit", 0, List.of(new UnitAdded(unitId, propertyId, name, baseRent)), List.of());
        jdbc.update("insert into pm_unit(unit_id, property_id, name, base_rent) values (?,?,?,?)",
            unitId, propertyId, name, baseRent);
        return unitId;
    }
}
