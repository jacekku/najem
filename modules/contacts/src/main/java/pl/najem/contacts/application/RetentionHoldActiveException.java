package pl.najem.contacts.application;

import java.util.List;
import java.util.UUID;

public class RetentionHoldActiveException extends RuntimeException {

    public RetentionHoldActiveException(UUID contactId, List<String> reasons) {
        super("Contact " + contactId + " cannot be erased, active retention holds: " + String.join(", ", reasons));
    }
}
