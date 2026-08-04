package pl.najem.pm.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
    private final Clock clock;

    public RentChangeProcess(ProcessDueStore due, TenancyService tenancies, Clock clock) {
        this.due = due;
        this.tenancies = tenancies;
        this.clock = clock;
    }

    /**
     * Rethrows so a failed subject reaches the scheduler's error handler and gets logged. The
     * throw costs nothing already done: every subject that succeeded committed in its own
     * transaction before this point. Silence here is the failure mode this whole shape exists
     * to avoid — a sweep that quietly does nothing, every minute, forever.
     */
    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        var result = runDue(LocalDate.now(clock));
        if (!result.clean()) {
            throw new IllegalStateException(
                "%s sweep could not process %s".formatted(KIND, result.failed()));
        }
    }

    /**
     * Separate from {@link #sweep()} so tests drive the date instead of the wall clock.
     *
     * <p>Deliberately NOT {@code @Transactional} — see {@link SweepResult}. Each tenancy is its
     * own unit of work inside {@link TenancyService}, so one unprocessable tenancy costs exactly
     * that tenancy instead of rolling back every rent change applied earlier in the same sweep.
     */
    public SweepResult runDue(LocalDate on) {
        int applied = 0;
        List<UUID> failed = new ArrayList<>();
        for (UUID tenancyId : due.due(KIND, on)) {
            try {
                if (tenancies.applyDueRentChange(tenancyId, on)) {
                    applied++;
                }
            } catch (RuntimeException ex) {
                failed.add(tenancyId);
            }
        }
        return new SweepResult(applied, List.copyOf(failed));
    }
}
