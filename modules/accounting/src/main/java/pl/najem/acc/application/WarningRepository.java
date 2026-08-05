package pl.najem.acc.application;

import java.util.List;
import java.util.UUID;

/**
 * The warning register: what was flagged, and what a manager has not looked at yet.
 *
 * <p>A record rather than a projection. Nothing here can be rebuilt from another table — {@code
 * seen} is a fact about a person having read something, and no amount of re-deriving charges would
 * produce it.
 */
public interface WarningRepository {

    /**
     * Files flags against a tenancy. Raised inside the transaction that caused them, so a rolled
     * back posting takes its warnings with it and a committed one can never lose them.
     */
    void raise(UUID workspaceId, UUID tenancyId, List<WarningToRaise> warnings);

    /** What the manager has not marked seen, oldest first. */
    List<Warning> unseen(UUID workspaceId);

    /** Another agency's warning is not yours to silence, so this is workspace-scoped. */
    void markSeen(UUID workspaceId, UUID warningId);
}
