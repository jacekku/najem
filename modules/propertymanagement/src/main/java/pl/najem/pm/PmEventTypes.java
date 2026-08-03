package pl.najem.pm;

import org.springframework.stereotype.Component;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.pm.domain.PropertyCreated;
import pl.najem.pm.domain.TenancyActivated;
import pl.najem.pm.domain.TenancyReserved;
import pl.najem.pm.domain.UnitAdded;

@Component
public class PmEventTypes {

    public PmEventTypes(EventTypeRegistry registry) {
        register(registry);
    }

    public static void register(EventTypeRegistry registry) {
        registry.register(PropertyCreated.class);
        registry.register(UnitAdded.class);
        registry.register(TenancyReserved.class);
        registry.register(TenancyActivated.class);
    }
}
