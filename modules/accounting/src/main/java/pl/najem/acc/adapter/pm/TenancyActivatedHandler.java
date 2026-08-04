package pl.najem.acc.adapter.pm;

import org.springframework.stereotype.Component;
import pl.najem.acc.application.LedgerService;
import pl.najem.acc.application.MonthlyBreakdown;
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
        // Warnings returned here (collapse rule, breakdown mismatch) are deliberately not surfaced
        // yet: PM already warns the manager at reservation, where the manager actually is. They get
        // a home on the reconciliation screen when it lands.
        ledger.postMonthlyCharges(event.workspaceId(), event.tenancyId(), breakdownOf(event),
            event.startDate(), event.paymentReference());
    }

    private static MonthlyBreakdown breakdownOf(TenancyActivatedEvent event) {
        return event.componentSplitInContract()
            ? MonthlyBreakdown.split(event.monthlyTotal(), event.rent(), event.adminFee(),
                event.mediaAdvance())
            : MonthlyBreakdown.unsplit(event.monthlyTotal());
    }
}
