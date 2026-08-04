package pl.najem.acc.domain;

/**
 * The rungs of the matching ladder, tried in order and stored with the suggestion so the manager
 * sees how the ledger arrived at it. A tier-2 guess and a tier-1 certainty are both suggestions,
 * but they do not deserve the same confidence from the person confirming them.
 */
public enum MatchTier {

    /** The reference and the amount both match an open charge. */
    EXACT(1),
    /** The reference is recognisable once case and separators are discarded; the amount may differ. */
    REFERENCE(2),
    /** No usable reference, but this account has paid for a tenancy before. */
    REMEMBERED_PAYER(3),
    /** Nothing the ledger can honestly claim. A human decides. */
    MANUAL(4);

    private final int number;

    MatchTier(int number) {
        this.number = number;
    }

    public int number() {
        return number;
    }
}
