package pl.najem.app.web;

/**
 * The hidden {@code contactId} field on the unit screen's form did not parse as a UUID.
 *
 * <p>Deliberately its own type, distinct from
 * {@link pl.najem.contacts.application.NoSuchContactException}: a malformed value is bad input from
 * whoever posted the form (400), while a well-formed id this workspace does not know is a workspace
 * boundary refusal indistinguishable from "does not exist" (404). Mapping both to the same status
 * would either hide a genuine 400 as a 404 or, worse, let a caller learn something about which ids
 * are merely garbled versus genuinely absent.
 */
public class InvalidContactIdException extends RuntimeException {

    public InvalidContactIdException(String contactId, Throwable cause) {
        super("Invalid contact id " + contactId, cause);
    }
}
