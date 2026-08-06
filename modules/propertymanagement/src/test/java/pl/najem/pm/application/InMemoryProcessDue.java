package pl.najem.pm.application;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProcessDueRepository} in a map keyed the way the table is — {@code (kind, subject_id)}.
 *
 * <p>The key is the mechanism worth copying. One timer per kind per subject is what makes
 * {@code armNextRentChange} necessary: arming a second change for the same tenancy overwrites the
 * first rather than queueing beside it. A map keyed on the subject alone would model that; a list
 * of armed timers would not, and would quietly pass a test that strands a rent change in Postgres.
 *
 * <p>{@code arm} clears the fired mark, exactly as {@code do update set ... fired_at = null} does,
 * so a re-armed timer becomes due again.
 */
public class InMemoryProcessDue implements ProcessDueRepository {

    private record Key(String kind, UUID subjectId) {}

    private record Timer(LocalDate dueOn, boolean fired) {}

    private final Map<Key, Timer> timers = new LinkedHashMap<>();

    @Override
    public void arm(String kind, UUID subjectId, LocalDate dueOn) {
        timers.put(new Key(kind, subjectId), new Timer(dueOn, false));
    }

    @Override
    public List<UUID> due(String kind, LocalDate on) {
        return timers.entrySet().stream()
            .filter(e -> e.getKey().kind().equals(kind))
            .filter(e -> !e.getValue().fired())
            .filter(e -> !e.getValue().dueOn().isAfter(on))
            .sorted(Comparator.comparing(e -> e.getValue().dueOn()))
            .map(e -> e.getKey().subjectId())
            .toList();
    }

    /** No row is not an error: a timer disarmed by another path leaves nothing to mark. */
    @Override
    public void markFired(String kind, UUID subjectId) {
        var key = new Key(kind, subjectId);
        var timer = timers.get(key);
        if (timer != null) {
            timers.put(key, new Timer(timer.dueOn(), true));
        }
    }

    @Override
    public void disarm(String kind, UUID subjectId) {
        timers.remove(new Key(kind, subjectId));
    }

    /** A test affordance: the date a timer is armed for, or empty when there is none. */
    public Optional<LocalDate> armedFor(String kind, UUID subjectId) {
        return Optional.ofNullable(timers.get(new Key(kind, subjectId))).map(Timer::dueOn);
    }
}
