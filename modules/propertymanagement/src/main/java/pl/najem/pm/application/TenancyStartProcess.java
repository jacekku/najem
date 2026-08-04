package pl.najem.pm.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
    private final Clock clock;

    public TenancyStartProcess(ProcessDueStore due, TenancyService tenancies, Clock clock) {
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
     * own unit of work, so one unprocessable tenancy costs exactly that tenancy.
     */
    public SweepResult runDue(LocalDate on) {
        int activated = 0;
        List<UUID> failed = new ArrayList<>();
        for (UUID tenancyId : due.due(KIND, on)) {
            try {
                if (tenancies.activateIfDue(tenancyId)) {
                    activated++;
                }
            } catch (RuntimeException ex) {
                failed.add(tenancyId);
            }
        }
        return new SweepResult(activated, List.copyOf(failed));
    }
}
