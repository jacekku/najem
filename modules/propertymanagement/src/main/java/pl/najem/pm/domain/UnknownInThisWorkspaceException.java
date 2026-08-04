package pl.najem.pm.domain;

/**
 * The subject does not exist in the caller's workspace — whether because it does not exist at
 * all or because it belongs to another agency. Deliberately one exception for both: telling a
 * caller "that exists, but not for you" confirms another agency's id is real.
 */
public class UnknownInThisWorkspaceException extends RuntimeException {

    public UnknownInThisWorkspaceException(String subject) {
        super("Not found in this workspace: " + subject);
    }
}
