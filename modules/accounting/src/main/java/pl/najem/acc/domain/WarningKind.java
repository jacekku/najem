package pl.najem.acc.domain;

/** What the manager is being told. The kind is stable; the detail carries the figures. */
public enum WarningKind {

    /** No contractual split, so the whole amount is rent — fully taxable and fully valorizable. */
    COLLAPSE_RULE("collapseRule"),
    /** The contractual breakdown does not sum to the agreed monthly total; the breakdown was charged. */
    BREAKDOWN_MISMATCH("breakdownMismatch"),
    /** An account now pays for more than one tenancy, so it can no longer identify one: tier 3 declines. */
    PAYER_ACCOUNT_AMBIGUOUS("payerAccountAmbiguous"),
    /** The deposit exceeds the statutory cap for this tenancy's legal form. Charged anyway; flagged. */
    DEPOSIT_CAP_EXCEEDED("depositCapExceeded"),
    /** PM sent a legal form this module has not been taught, so no cap could be checked. */
    UNKNOWN_LEGAL_FORM("unknownLegalForm");

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
