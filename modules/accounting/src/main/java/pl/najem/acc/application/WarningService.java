package pl.najem.acc.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The warning register, as the rest of the module reaches it.
 *
 * <p>Warnings never block a posting — this is an expert system: it flags, the manager decides. That
 * rule is expressed by what this class does not have rather than by anything it does: no method
 * here can refuse anything.
 *
 * <p>So it is a thin pass-through onto {@link WarningRepository}, deliberately. It stays because it
 * is the boundary the controller drives — a driving adapter reaching a repository directly would
 * put the REST layer one arrow further in than the layering allows — and because the day a warning
 * acquires a rule (deduplication, expiry, a kind that supersedes another), this is where it lands
 * and no caller changes.
 */
@Service
@Transactional
public class WarningService {

    private final WarningRepository warnings;

    public WarningService(WarningRepository warnings) {
        this.warnings = warnings;
    }

    /**
     * Raised inside the transaction that caused them, so a rolled back posting takes its warnings
     * with it and a committed one can never lose them. That is the caller's transaction, not one
     * started here.
     */
    public void raise(UUID workspaceId, UUID tenancyId, List<WarningToRaise> raised) {
        warnings.raise(workspaceId, tenancyId, raised);
    }

    public List<Warning> unseen(UUID workspaceId) {
        return warnings.unseen(workspaceId);
    }

    public void markSeen(UUID workspaceId, UUID warningId) {
        warnings.markSeen(workspaceId, warningId);
    }
}
