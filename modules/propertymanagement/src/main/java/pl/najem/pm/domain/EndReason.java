package pl.najem.pm.domain;

/**
 * Why a tenancy stopped. Widened from the original four during planning (v1.1) because the
 * narrow set forced real endings into MUTUAL_AGREEMENT and lost the distinction Reporting and
 * Accounting both need.
 *
 * <p>ERROR_ANNULLED is the one that is not an ending at all: it records that the tenancy should
 * never have existed. The event stays in the stream — an event store does not forget — but
 * Reporting keeps annulled tenancies out of occupancy statistics, so the reason has to reach
 * them rather than being flattened into "ended".
 */
public enum EndReason {

    /** The fixed term ran out. */
    AGREEMENT_EXPIRY,
    /** Both sides agreed to stop early. */
    MUTUAL_AGREEMENT,
    TENANT_NOTICE,
    LANDLORD_NOTICE,
    /** art. 8a ust. 5: the tenant refused a unilateral increase, which terminates the tenancy. */
    RENT_INCREASE_REFUSAL,
    /** art. 19d/19i: the instytucjonalny and okazjonalny demand-to-vacate path. */
    VACATE_DEMAND,
    COURT_EVICTION,
    /** Not an ending — a mistaken reservation or activation being taken back. */
    ERROR_ANNULLED;

    /**
     * The wire form crossing the module boundary. Deliberately not {@code name()}: the enum is
     * PM's to rename, the string is a published contract, and coupling them means a refactor
     * inside PM silently changes what Accounting and Reporting read.
     */
    public String wireName() {
        return name().toLowerCase().replace('_', '-');
    }
}
