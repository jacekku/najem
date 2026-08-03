package pl.najem.pm;

import org.springframework.stereotype.Component;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.pm.domain.PropertyEvents;
import pl.najem.pm.domain.TenancyActivated;
import pl.najem.pm.domain.TenancyReserved;
import pl.najem.pm.domain.UnitEvents;

import java.util.List;

@Component
public class PmEventTypes {

    public PmEventTypes(EventTypeRegistry registry) {
        register(registry);
    }

    /** Every PM event class must appear here or it will not deserialize (gate convention 4). */
    public static List<Class<?>> allRegistered() {
        return List.of(
            PropertyEvents.PropertyCreated.class,
            PropertyEvents.PropertyRentTargetSet.class,
            PropertyEvents.PropertyOwnershipChanged.class,
            PropertyEvents.PropertyDetailsUpdated.class,
            UnitEvents.UnitAddedToProperty.class,
            TenancyReserved.class,
            TenancyActivated.class);
    }

    public static void register(EventTypeRegistry registry) {
        allRegistered().forEach(registry::register);
    }
}
