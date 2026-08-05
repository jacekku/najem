package pl.najem.acc.domain;

/**
 * Where a payment stands. Every state a row of acc_payment can be in, not only the ones allocation
 * puts it in — the column is one vocabulary, and a type that knew half of it would refuse to read
 * rows the rest of the module writes.
 */
public enum PaymentStatus {

    /** Arrived, and nobody has said whose it is. The manual queue is everything in this state. */
    UNMATCHED("unmatched"),
    /** The matching ladder has a candidate tenancy, awaiting a manager's confirmation. */
    SUGGESTED("suggested"),
    /** Some of it came to rest; the remainder is the tenant's credit and keeps waiting. */
    PARTIALLY_ALLOCATED("partially-allocated"),
    /** Spent to the last grosz against the tenant's obligations. */
    ALLOCATED("allocated"),
    /** The bank took it back. The money was never there, so nothing it settled is settled. */
    REVERSED("reversed"),
    /** Never a tenant's money — an outgoing debit, a bank fee, an owner's own transfer. */
    NON_TENANT("non-tenant");

    private final String wireName;

    PaymentStatus(String wireName) {
        this.wireName = wireName;
    }

    /** The value as the status column carries it. */
    public String wireName() {
        return wireName;
    }

    public static PaymentStatus of(String wireName) {
        for (PaymentStatus status : values()) {
            if (status.wireName.equals(wireName)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown payment status: " + wireName);
    }
}
