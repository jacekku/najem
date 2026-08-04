package pl.najem.pm.domain;

/**
 * What a stored file is. PM holds a reference, never the bytes.
 *
 * <p>NOTARIAL_DECLARATION is the only one with behaviour attached: art. 19f ust. 3 requires the
 * tenant's submission-to-execution declaration for an instytucjonalny tenancy, and its absence
 * stops the start process from activating one unattended.
 */
public enum DocType {

    AGREEMENT,
    ANNEX,
    NOTICE,
    GUARANTOR_SURETY,
    INSURANCE_POLICY,
    NOTARIAL_DECLARATION,
    OTHER;

    public String wireName() {
        return name().toLowerCase().replace('_', '-');
    }
}
