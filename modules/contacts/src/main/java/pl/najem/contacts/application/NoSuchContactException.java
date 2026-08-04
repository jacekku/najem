package pl.najem.contacts.application;

import java.util.UUID;

/**
 * No contact with this id in this workspace — which deliberately does not distinguish "no such
 * contact anywhere" from "somebody else's contact".
 * <p>
 * The two must be indistinguishable to a caller. A different response for a foreign id would turn
 * every contacts endpoint into an oracle answering "does this UUID name a real person at another
 * agency", which is precisely the fact the workspace boundary exists to withhold. So the message
 * names the id the caller supplied and says nothing about where else it might live.
 */
public class NoSuchContactException extends RuntimeException {

    public NoSuchContactException(UUID contactId) {
        super("No contact " + contactId);
    }
}
