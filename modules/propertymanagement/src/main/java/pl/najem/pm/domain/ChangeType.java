package pl.najem.pm.domain;

/**
 * AGREED_CHANGE is a bilateral change. UNILATERAL_INCREASE is the landlord acting alone and
 * is what art. 8a/9 constrains (3-month notice, 6-month frequency). INDEXATION is a clause
 * the contract already contains.
 */
public enum ChangeType { AGREED_CHANGE, UNILATERAL_INCREASE, INDEXATION;

    public String wireName() {
        return name().toLowerCase();
    }
}
