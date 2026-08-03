package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.Property;
import pl.najem.pm.domain.UnitEvents;

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

    public UUID createProperty(UUID workspaceId, String address, List<Owner> owners) {
        UUID propertyId = UUID.randomUUID();
        store.append(propertyId, "Property", 0,
            Property.create(propertyId, workspaceId, address, owners), List.of());
        jdbc.update("insert into pm_property(property_id, workspace_id, address) values (?,?,?)",
            propertyId, workspaceId, address);
        return propertyId;
    }

    public UUID addUnit(UUID propertyId, String name, BigDecimal baseRent) {
        UUID workspaceId = workspaceOf(propertyId);
        UUID unitId = UUID.randomUUID();
        store.append(unitId, "Unit", 0,
            List.of(new UnitEvents.UnitAddedToProperty(workspaceId, unitId, propertyId, name, baseRent)),
            List.of());
        jdbc.update("insert into pm_unit(unit_id, property_id, workspace_id, name, base_rent) "
            + "values (?,?,?,?,?)", unitId, propertyId, workspaceId, name, baseRent);
        return unitId;
    }

    /**
     * The workspace of a property, read from its own stream. Children never take a
     * caller-supplied workspaceId — that makes cross-workspace writes structurally impossible
     * rather than merely validated.
     */
    public UUID workspaceOf(UUID propertyId) {
        return Property.from(store.load(propertyId).events()).workspaceId();
    }
}
