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
 */
public record ScenarioRequest(String name, String iban, String reference,
                              BigDecimal amount, LocalDate anchorDate) {
}
