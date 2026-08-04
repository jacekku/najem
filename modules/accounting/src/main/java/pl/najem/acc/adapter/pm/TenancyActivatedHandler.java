package pl.najem.acc.adapter.pm;

import org.springframework.stereotype.Component;
import pl.najem.acc.application.DepositService;
import pl.najem.acc.application.LedgerService;
import pl.najem.acc.application.MonthlyBreakdown;
import pl.najem.contracts.events.IntegrationEventHandler;
import pl.najem.contracts.events.TenancyActivatedEvent;

import java.math.BigDecimal;

/** Tenancy Accounting ACL: translates PM facts into ledger commands. */
@Component
public class TenancyActivatedHandler implements IntegrationEventHandler<TenancyActivatedEvent> {

    private final LedgerService ledger;
    private final DepositService deposits;

    public TenancyActivatedHandler(LedgerService ledger, DepositService deposits) {
        this.ledger = ledger;
        this.deposits = deposits;
    }

    @Override
    public Class<TenancyActivatedEvent> eventType() {
        return TenancyActivatedEvent.class;
    }

    @Override
    public void handle(TenancyActivatedEvent event) {
        var breakdown = breakdownOf(event);
        ledger.postMonthlyCharges(event.workspaceId(), event.tenancyId(), breakdown,
            event.startDate(), event.paymentReference());
        // A null depositAmount is a contract without a deposit, which the service refuses to turn
        // into a zero-value charge. The multiple is of the czynsz, not the monthly total.
        deposits.chargeOnActivation(event.workspaceId(), event.tenancyId(), event.depositAmount(),
            rentBase(breakdown), event.legalForm(), event.startDate(),
            "KAUCJA/" + event.paymentReference());
    }

    private static MonthlyBreakdown breakdownOf(TenancyActivatedEvent event) {
        return event.componentSplitInContract()
            ? MonthlyBreakdown.split(event.monthlyTotal(), event.rent(), event.adminFee(),
                event.mediaAdvance())
            : MonthlyBreakdown.unsplit(event.monthlyTotal());
    }

    /**
     * The czynsz the deposit is a multiple of. Under the collapse rule a contract with no split has
     * a rent equal to its whole monthly total — that is the intended consequence of not splitting,
     * not an approximation.
     */
    private static BigDecimal rentBase(MonthlyBreakdown breakdown) {
        return breakdown.chargeLines().stream()
            .filter(line -> line.component() == pl.najem.acc.domain.Component.RENT)
            .map(line -> line.amount())
            .findFirst()
            .orElse(BigDecimal.ZERO);
    }
}
