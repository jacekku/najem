package pl.najem.contacts.application;

import java.util.UUID;

/**
 * The interest exists and belongs to this workspace, but has already been withdrawn or converted.
 *
 * <p>Distinct from {@link NoSuchInterestException} on purpose: that one merges unknown with foreign
 * and must stay a 404 disclosing nothing. This one is a state the caller may legitimately know
 * about, because they own it.
 */
public class InterestNotActiveException extends RuntimeException {

    public InterestNotActiveException(UUID interestId, String status) {
        super("Interest " + interestId + " is " + status);
    }
}
