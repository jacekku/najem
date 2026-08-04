package pl.najem.acc.domain;

/**
 * The ledger dimension on every charge line (accounting-domain-model §2). Components are a
 * dimension, never separate accounts: the tenant sees one total, the contract carries the legal
 * split, and the ledger keeps the split as data.
 */
public enum Component {

    /** Czynsz najmu. The base for deposit valorization and for the ryczałt income projection. */
    RENT("rent"),
    /** Opłaty administracyjne the tenant expressly assumes (contractual pass-through). */
    ADMIN_FEE("adminFee"),
    /** Zaliczki na opłaty niezależne — media at cost, drained by the true-up. */
    MEDIA_ADVANCE("mediaAdvance"),
    DEPOSIT("deposit"),
    /** Nota obciążeniowa basis: a repair bill recharged to the tenancy. */
    REPAIR_RECHARGE("repairRecharge"),
    /** Late-payment interest — a right, not a duty (KC art. 481), so it is charged only on instruction. */
    INTEREST("interest");

    private final String wireName;

    Component(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Where this component sits in the allocation order within one due date: interest, then media,
     * then admin, then repair recharges, and rent last.
     *
     * <p>Rent last is the load-bearing part. Rent is what the arrears board and the art. 11
     * full-periods counter watch, so if something must stay unpaid it should be the obligation whose
     * consequences stay visible — settling rent first would clear the alarm and leave the debt.
     */
    public int allocationRank() {
        return switch (this) {
            case INTEREST -> 1;
            case MEDIA_ADVANCE -> 2;
            case ADMIN_FEE -> 3;
            case REPAIR_RECHARGE -> 4;
            case RENT -> 5;
            case DEPOSIT -> Integer.MAX_VALUE;
        };
    }

    /**
     * A deposit is a separate obligation met by a separate transfer, so rent money must not drift
     * onto it as a side effect of the monthly cycle. Settling a deposit is an explicit act.
     */
    public boolean settledByAutomaticAllocation() {
        return this != DEPOSIT;
    }

    public String wireName() {
        return wireName;
    }

    public static Component of(String wireName) {
        for (Component component : values()) {
            if (component.wireName.equals(wireName)) {
                return component;
            }
        }
        throw new IllegalArgumentException("Unknown component: " + wireName);
    }
}
