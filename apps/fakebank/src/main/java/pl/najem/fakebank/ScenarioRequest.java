package pl.najem.fakebank;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Parameters for seeding one scenario.
 *
 * @param name       scenario name from {@link ScenarioCatalog#names()}
 * @param iban       account to seed into
 * @param reference  the tenancy's payment reference, as it would appear in a transfer title
 * @param amount     the charge being paid; scenarios express their amounts relative to it
 * @param anchorDate the charge's due date; scenarios express their dates relative to it
 * @param secondReference a <em>second, independent</em> tenancy's reference. Null for every
 *                        scenario but {@code lump-sum-two-tenancies}, which cannot be built without
 *                        it: the whole point of that fixture is that the two references belong to
 *                        different tenancies, so deriving one from the other would produce a
 *                        relationship the real case does not have.
 */
public record ScenarioRequest(String name, String iban, String reference,
                              BigDecimal amount, LocalDate anchorDate, String secondReference) {

    /**
     * The shape this record began as, for the twelve scenarios that concern one tenancy. Kept as a
     * constructor rather than a factory so the widening is purely additive — every existing caller
     * compiles unchanged.
     */
    public ScenarioRequest(String name, String iban, String reference,
                           BigDecimal amount, LocalDate anchorDate) {
        this(name, iban, reference, amount, anchorDate, null);
    }
}
