package pl.najem.acc.domain;

/** What the manager is being told. The kind is stable; the detail carries the figures. */
public enum WarningKind {

    /** No contractual split, so the whole amount is rent — fully taxable and fully valorizable. */
    COLLAPSE_RULE("collapseRule"),
    /** The contractual breakdown does not sum to the agreed monthly total; the breakdown was charged. */
    BREAKDOWN_MISMATCH("breakdownMismatch"),
    /** An account that paid for one tenancy has been confirmed against another; tier 3 now follows the newer one. */
    PAYER_ACCOUNT_REASSIGNED("payerAccountReassigned");

    private final String wireName;

    WarningKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static WarningKind of(String wireName) {
        for (WarningKind kind : values()) {
            if (kind.wireName.equals(wireName)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown warning kind: " + wireName);
    }
}
