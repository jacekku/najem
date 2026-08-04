package pl.najem.pm.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.Tenancy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Start date reached + pre-activation checklist complete -> activate.
 *
 * <p>An incomplete checklist does NOT cancel anything: the timer stays armed and the tenancy
 * activates late, carrying its agreed start date. There is no prorating (domain model §5) —
 * the tenant owes the full month regardless of when the keys actually changed hands.
 */
@Component
public class TenancyStartProcess {

    public static final String KIND = "tenancy-start";

    private final ProcessDueStore due;
    private final TenancyService tenancies;
    private final EventStore store;
    private final Clock clock;

    public TenancyStartProcess(ProcessDueStore due, TenancyService tenancies,
                               EventStore store, Clock clock) {
        this.due = due;
        this.tenancies = tenancies;
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        runDue(LocalDate.now(clock));
    }

    /** Separate from {@link #sweep()} so tests drive the date instead of the wall clock. */
    @Transactional
    public int runDue(LocalDate on) {
        int activated = 0;
        for (UUID tenancyId : due.due(KIND, on)) {
            var tenancy = Tenancy.from(store.load(tenancyId).events());
            if (tenancy.state() != Tenancy.State.RESERVED) {
                due.disarm(KIND, tenancyId);
                continue;
            }
            if (!tenancy.checklistComplete(ChecklistPhase.PRE_ACTIVATION)
                    || !tenancy.autoActivationAllowed()) {
                continue;   // stays armed; the manager is still being prompted
            }
            tenancies.activate(tenancyId, tenancy.startDate());
            due.markFired(KIND, tenancyId);
            activated++;
        }
        return activated;
    }
}
