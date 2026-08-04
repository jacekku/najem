package pl.najem.pm;

import org.springframework.stereotype.Component;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.pm.domain.PropertyEvents;
import pl.najem.pm.domain.TenancyEvents;
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
            UnitEvents.UnitBaseRentSet.class,
            UnitEvents.UnitDetailsUpdated.class,
            UnitEvents.UnitOpenedToRent.class,
            UnitEvents.UnitClosedToRent.class,
            UnitEvents.UnitRemovedFromProperty.class,
            UnitEvents.TenancyPeriodRegistered.class,
            UnitEvents.TenancyPeriodReleased.class,
            TenancyEvents.TenancyReserved.class,
            TenancyEvents.TenantAddedToTenancy.class,
            TenancyEvents.TenantRemovedFromTenancy.class,
            TenancyEvents.TenancyReservationCancelled.class,
            TenancyEvents.TenancyActivated.class,
            TenancyEvents.ChecklistItemAdded.class,
            TenancyEvents.ChecklistItemCompleted.class,
            TenancyEvents.HandoverProtocolRecorded.class);
    }

    public static void register(EventTypeRegistry registry) {
        allRegistered().forEach(registry::register);
    }
}
