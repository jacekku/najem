package pl.najem.acc;

import org.springframework.stereotype.Component;
import pl.najem.acc.domain.ChargePosted;
import pl.najem.acc.domain.PaymentAllocated;
import pl.najem.acc.domain.PaymentIngested;
import pl.najem.eventstore.EventTypeRegistry;

@Component
public class AccEventTypes {

    public AccEventTypes(EventTypeRegistry registry) {
        register(registry);
    }

    public static void register(EventTypeRegistry registry) {
        registry.register(ChargePosted.class);
        registry.register(PaymentIngested.class);
        registry.register(PaymentAllocated.class);
    }
}
