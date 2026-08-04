package pl.najem.pm.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One month before a tenancy is due to end, prompt the manager (domain model §2 item 18). The
 * lead time is fixed at a month on purpose — a configurable one is a confirmed MVP skip.
 *
 * <p>This only prompts. Ending is always a human act: a tenancy that reaches its end date and
 * is neither ended nor renewed stays active, because in Polish practice it often genuinely
 * continues, and a system that ended it automatically would be inventing a legal fact.
 */
@Component
public class EndOfTenancyProcess {

    public static final String KIND = "tenancy-ending-soon";

    /**
     * Armed by {@link TenancyService#end}, read by the manager's attention list. PM records this
     * deadline; the deposit lifecycle itself is Accounting's, and they compute the authoritative
     * date from vacateDate and the move-out protocol.
     */
    public static final String DEPOSIT_SETTLEMENT_KIND = "deposit-settlement";

    private final ProcessDueStore due;
    private final TenancyService tenancies;
    private final Clock clock;

    public EndOfTenancyProcess(ProcessDueStore due, TenancyService tenancies, Clock clock) {
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
     * Deliberately NOT {@code @Transactional}: each subject is its own unit of work inside
     * {@link TenancyService}, so one unprocessable tenancy costs exactly that tenancy. A single
     * transaction around the loop would let one bad subject roll back every other tenancy's work
     * and throw again on the next sweep, forever, having committed nothing.
     */
    public SweepResult runDue(LocalDate on) {
        int done = 0;
        List<UUID> failed = new ArrayList<>();
        for (UUID tenancyId : due.due(KIND, on)) {
            try {
                if (tenancies.flagEndingSoonIfDue(tenancyId, on)) {
                    done++;
                }
            } catch (RuntimeException ex) {
                failed.add(tenancyId);
            }
        }
        return new SweepResult(done, List.copyOf(failed));
    }
}
