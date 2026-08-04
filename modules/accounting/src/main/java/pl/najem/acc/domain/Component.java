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
