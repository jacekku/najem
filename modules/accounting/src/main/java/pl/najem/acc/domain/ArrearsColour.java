package pl.najem.acc.domain;

/**
 * The arrears board — the product's face. One colour per tenancy, and per property above it.
 *
 * <p>The colours are ordered by how much attention they demand, and the two that matter most are
 * the ends. <strong>Bright red counts full unpaid periods, not amounts</strong>: art. 11 u.o.p.l.
 * makes termination available after three full periods in arrears, and a tenant three months behind
 * by 100 zł each is in a different legal position from one a single month behind by 3000 zł, even
 * though the second owes more money.
 *
 * <p>Yellow exists so that red means something. A charge posted the day before it is due is not
 * arrears, and colouring it red on the day it appears would put every tenancy in the portfolio into
 * the alarm state on the ninth of every month.
 */
public enum ArrearsColour {

    /** Paid through the end of the tenancy — nothing further is owed, ever. */
    GOLDEN("golden"),
    /** Nothing owed today. */
    GREEN("green"),
    /** Something is unpaid but not yet due. Not arrears. */
    YELLOW("yellow"),
    /** Past due and unpaid, from the first day. */
    RED("red"),
    /** At least one whole billing period unpaid — the art. 11 counter is running. */
    BRIGHT_RED("brightRed");

    private final String wireName;

    ArrearsColour(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ArrearsColour of(String wireName) {
        for (ArrearsColour colour : values()) {
            if (colour.wireName.equals(wireName)) {
                return colour;
            }
        }
        throw new IllegalArgumentException("Unknown arrears colour: " + wireName);
    }
}
