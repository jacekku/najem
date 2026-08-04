package pl.najem.pm.application;

import java.util.List;
import java.util.UUID;

/**
 * What one sweep of a process manager did, including what it could not do.
 *
 * <p>The failure list exists because the alternative is worse in both directions: let the
 * exception escape and one bad subject rolls back the whole sweep and re-throws every minute
 * forever; swallow it and the subject is skipped in silence. Neither is visible to anybody.
 * Each subject is processed in its own transaction, so a failure costs exactly that subject.
 */
public record SweepResult(int done, List<UUID> failed) {

    public boolean clean() {
        return failed.isEmpty();
    }
}
