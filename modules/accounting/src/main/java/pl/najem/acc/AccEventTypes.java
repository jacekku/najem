package pl.najem.acc;

import org.springframework.stereotype.Component;
import pl.najem.acc.domain.ChargeDeactivated;
import pl.najem.acc.domain.ChargePosted;
import pl.najem.acc.domain.CreditNoteIssued;
import pl.najem.acc.domain.DepositCharged;
import pl.najem.acc.domain.PaymentAllocated;
import pl.najem.acc.domain.PaymentIngested;
import pl.najem.acc.domain.PaymentAllocationAmended;
import pl.najem.acc.domain.PaymentMarkedNonTenant;
import pl.najem.acc.domain.PaymentReversed;
import pl.najem.eventstore.EventTypeRegistry;

@Component
public class AccEventTypes {

    public AccEventTypes(EventTypeRegistry registry) {
        register(registry);
    }

    public static void register(EventTypeRegistry registry) {
        registry.register(ChargePosted.class);
        registry.register(ChargeDeactivated.class);
        registry.register(CreditNoteIssued.class);
        registry.register(DepositCharged.class);
        registry.register(PaymentIngested.class);
        registry.register(PaymentAllocated.class);
        registry.register(PaymentMarkedNonTenant.class);
        registry.register(PaymentReversed.class);
        registry.register(PaymentAllocationAmended.class);
    }
}
