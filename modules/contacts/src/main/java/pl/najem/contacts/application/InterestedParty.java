package pl.najem.contacts.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of "who wants this unit" — the interest and the person in one answer.
 *
 * <p>{@code willingToPay} and {@code desiredStart} are nullable. Somebody who rings to ask what the
 * agency would take for a flat has named neither, and a screen that demanded both would either
 * refuse to record the call or invent a number.
 */
public record InterestedParty(UUID interestId, UUID contactId, String givenName, String surname,
                              String email, String phone, BigDecimal willingToPay,
                              LocalDate desiredStart) {

    /** For a template, which should not be assembling a person's name from parts. */
    public String fullName() {
        return givenName + " " + surname;
    }
}
