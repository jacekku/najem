package pl.najem.pm.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.Tenancy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One day before a scheduled change takes effect, if it still stands, make it the rent in force
 * and tell Accounting (domain model §5). A change edited or cancelled before that day sends
 * nothing — which is why the process re-reads the aggregate instead of trusting the timer.
 */
@Component
public class RentChangeProcess {

    public static final String KIND = "rent-change";

    private final ProcessDueStore due;
    private final TenancyService tenancies;
    private final EventStore store;
    private final Clock clock;

    public RentChangeProcess(ProcessDueStore due, TenancyService tenancies,
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
        int applied = 0;
        for (UUID tenancyId : due.due(KIND, on)) {
            var tenancy = Tenancy.from(store.load(tenancyId).events());
            var next = tenancy.nextPendingRentChange();
            if (next.isEmpty()) {
                due.disarm(KIND, tenancyId);
                continue;
            }
            var change = next.get();
            if (change.effectiveFrom().minusDays(1).isAfter(on)) {
                // The armed date moved later (the change was rescheduled) — re-arm, don't fire.
                due.arm(KIND, tenancyId, change.effectiveFrom().minusDays(1));
                continue;
            }
            tenancies.applyRentChange(tenancyId, change.effectiveFrom());
            applied++;

            // A tenancy may have several changes queued; arm the next one rather than
            // leaving it stranded behind a fired timer.
            var later = Tenancy.from(store.load(tenancyId).events()).nextPendingRentChange();
            if (later.isPresent()) {
                due.arm(KIND, tenancyId, later.get().effectiveFrom().minusDays(1));
            } else {
                due.markFired(KIND, tenancyId);
            }
        }
        return applied;
    }
}
