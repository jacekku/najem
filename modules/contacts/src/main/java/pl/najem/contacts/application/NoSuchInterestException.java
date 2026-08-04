package pl.najem.contacts.application;

import java.util.UUID;

/**
 * No interest with this id in this workspace — and, like {@link NoSuchContactException},
 * deliberately not distinguishing "no such interest anywhere" from "somebody else's".
 * <p>
 * Separate from {@code NoSuchContactException} because the two name different subjects and a
 * caller debugging a 404 should learn which id was rejected. They map to the same status and the
 * same empty body, so the pair discloses nothing the single type would not.
 */
public class NoSuchInterestException extends RuntimeException {

    public NoSuchInterestException(UUID interestId) {
        super("No interest " + interestId);
    }
}
