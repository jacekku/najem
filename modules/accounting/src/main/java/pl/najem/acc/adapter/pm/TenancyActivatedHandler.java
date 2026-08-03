package pl.najem.acc.adapter.pm;

import org.springframework.stereotype.Component;
import pl.najem.acc.application.LedgerService;
import pl.najem.contracts.events.IntegrationEventHandler;
import pl.najem.contracts.events.TenancyActivatedEvent;

/** Tenancy Accounting ACL: translates PM facts into ledger commands. */
@Component
public class TenancyActivatedHandler implements IntegrationEventHandler<TenancyActivatedEvent> {

    private final LedgerService ledger;

    public TenancyActivatedHandler(LedgerService ledger) {
        this.ledger = ledger;
    }

    @Override
    public Class<TenancyActivatedEvent> eventType() {
        return TenancyActivatedEvent.class;
    }

    @Override
    public void handle(TenancyActivatedEvent event) {
        ledger.postRentCharge(event.tenancyId(), event.monthlyRent(), event.startDate(),
            event.paymentReference());
    }
}
