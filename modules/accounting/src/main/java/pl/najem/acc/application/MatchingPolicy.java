package pl.najem.acc.application;

/**
 * How far down the ladder the ledger is allowed to go, and whether it may act on what it finds.
 *
 * <p>Both are off by launch decision, not by accident. Tiers 2-4 are built so they can be switched
 * on against real statements once a manager has watched the suggestions for a while; auto-confirm
 * stays off because allocating a stranger's money to a tenancy is not a mistake a machine should be
 * able to make unattended.
 */
public record MatchingPolicy(boolean tiersEnabled, boolean autoConfirm) {

    /** The launch default: exact reference and amount only, and never allocate unattended. */
    public static MatchingPolicy tierOneOnly() {
        return new MatchingPolicy(false, false);
    }

    /** The whole ladder, still suggesting rather than allocating. */
    public static MatchingPolicy tiersOn() {
        return new MatchingPolicy(true, false);
    }
}
