package pl.najem.app.web;

import java.util.UUID;

/**
 * The same person named twice on one reservation, or named as both tenant and guarantor.
 *
 * <p>Refused here because {@code Tenancy} has no rule against it and would carry the duplicate into
 * {@code TenancyReserved} — where it becomes two tenants who are one person, forever.
 */
public class DuplicatePartyException extends RuntimeException {

    public DuplicatePartyException(UUID contactId) {
        super("Contact " + contactId + " is already named on this reservation");
    }
}
