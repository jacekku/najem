package pl.najem.acc.domain;

/**
 * How long money has been sitting without an owner.
 *
 * <p>The risk is not that a line is unmatched today — that is ordinary. It is that nobody looks at
 * it for a month, by which time the payer has forgotten the transfer and the evidence for what it
 * was has gone cold.
 *
 * <p>Thresholds are the accountant's to set (hotspot P19, still open). The defaults come from the
 * domain model: notify same day, a decision expected within the week, red at a month.
 */
public enum SuspenseAge {

    /** Inside the week a decision is expected to take. */
    FRESH("fresh"),
    /** Past the expected decision window; someone should be working it. */
    WARN("warn"),
    /** A month unresolved. This is the state that turns into a dispute. */
    RED("red");

    private final String wireName;

    SuspenseAge(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static SuspenseAge of(int daysWaiting, int warnAfterDays, int redAfterDays) {
        if (daysWaiting >= redAfterDays) {
            return RED;
        }
        return daysWaiting >= warnAfterDays ? WARN : FRESH;
    }
}
