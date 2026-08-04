package pl.najem.pm.domain;

/**
 * Fixed at signing, per tenancy (v1.1 amendment): running zwykły tenancies stay zwykły
 * even after the property changes hands. OKAZJONALNY exists for completeness; its processes
 * (14-day tax registration, replacement premises) are deliberately not built.
 */
public enum LegalForm {

    ZWYKLY(12),
    OKAZJONALNY(6),
    INSTYTUCJONALNY(6);

    private final int depositCapMultiplier;

    LegalForm(int depositCapMultiplier) {
        this.depositCapMultiplier = depositCapMultiplier;
    }

    /**
     * Statutory deposit cap as a multiple of the monthly total. INSTYTUCJONALNY is 6x per
     * art. 19f ust. 5 — raised from 3x by the 2019 KZN amendment (research C1, coordinator
     * ruling seq 41). Anything quoting 3x is out of date. PM warns; the authoritative
     * per-legalForm gate is the Tenancy Accounting ACL's compliance seat.
     */
    public int depositCapMultiplier() {
        return depositCapMultiplier;
    }

    /** Wire form for the integration contract, which carries legalForm as a String. */
    public String wireName() {
        return name().toLowerCase();
    }
}
