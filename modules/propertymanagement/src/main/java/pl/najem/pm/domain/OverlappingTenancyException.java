package pl.najem.pm.domain;

/**
 * The ONE hard invariant in the PM domain: two tenancies cannot overlap in time on a unit.
 * Everything else in this bounded context is a soft check that warns and lets the manager decide.
 *
 * <p>It carries the period that <em>blocks</em>, not only a sentence about it. A screen has to tell
 * the manager when the unit frees up, and the only alternatives to a field are parsing the message
 * back apart or asking the unit a second question and hoping the answer has not moved. The message
 * stays for logs and for the API, which has no screen to render into.
 */
public class OverlappingTenancyException extends IllegalStateException {

    private final transient TenancyPeriod blocking;

    public OverlappingTenancyException(String message, TenancyPeriod blocking) {
        super(message);
        this.blocking = blocking;
    }

    /**
     * The already-registered period the candidate ran into.
     *
     * <p>{@code blocking.end()} is null for an indefinite tenancy, which is not a missing value: it
     * is the answer that the unit does not free up at all until that tenancy is ended. A caller that
     * renders a blank there has turned a fact into a gap.
     *
     * <p>When it is non-null it is also the first day the unit is available, not the last day it is
     * taken — periods are half-open, so a tenancy ending 30 Jun leaves 30 Jun free for the next one.
     * See {@link TenancyPeriod#overlaps}.
     */
    public TenancyPeriod blocking() {
        return blocking;
    }
}
