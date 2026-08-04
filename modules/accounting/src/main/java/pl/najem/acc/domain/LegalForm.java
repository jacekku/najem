package pl.najem.acc.domain;

import java.util.Optional;

/**
 * The statutory deposit caps, in months of czynsz. They are warn-gates, never walls — a manager with
 * a reason to exceed one is doing something the ledger should record and flag, not refuse.
 *
 * <p><strong>Instytucjonalny is six months, not three</strong> (art. 19f ust. 5). The 3× figure is
 * in wide circulation and is wrong; a cap set too low warns on lawful tenancies, and a warning that
 * fires on lawful facts teaches managers to dismiss the ones that matter.
 *
 * <p>The form arrives from PM as a String on purpose — an enum in a shared contract is a versioning
 * trap — so an unrecognised value is an ordinary possibility here, not a corrupt event.
 */
public enum LegalForm {

    /** Ustawa o ochronie praw lokatorów, art. 6 ust. 1. */
    ZWYKLY(12),
    /** Art. 19a ust. 4. */
    OKAZJONALNY(6),
    /** Art. 19f ust. 5. */
    INSTYTUCJONALNY(6);

    private final int depositCapInMonths;

    LegalForm(int depositCapInMonths) {
        this.depositCapInMonths = depositCapInMonths;
    }

    public int depositCapInMonths() {
        return depositCapInMonths;
    }

    /** Empty when PM sends a form this module has not been taught — the caller must not assume none. */
    public static Optional<LegalForm> of(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        for (LegalForm form : values()) {
            if (form.name().equalsIgnoreCase(wireName)) {
                return Optional.of(form);
            }
        }
        return Optional.empty();
    }
}
