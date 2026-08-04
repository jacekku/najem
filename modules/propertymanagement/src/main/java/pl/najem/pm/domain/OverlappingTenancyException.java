package pl.najem.pm.domain;

/**
 * The ONE hard invariant in the PM domain: two tenancies cannot overlap in time on a unit.
 * Everything else in this bounded context is a soft check that warns and lets the manager decide.
 */
public class OverlappingTenancyException extends IllegalStateException {

    public OverlappingTenancyException(String message) {
        super(message);
    }
}
