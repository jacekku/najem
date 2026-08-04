package pl.najem.acc.application;

import java.util.UUID;

/**
 * A workspace asked to ingest its bank statement and no account has been registered for it.
 *
 * <p>Refusing is the point. The alternative — falling back to a deployment-wide account — is how one
 * transfer came to be credited in two agencies' books, and an unconfigured workspace is precisely
 * the one that would have received somebody else's statement. Rule 7: where the answer to "whose
 * money is this?" is absent, the module stops rather than guessing.
 */
public class NoBankAccountRegisteredException extends RuntimeException {

    public NoBankAccountRegisteredException(UUID workspaceId) {
        super("workspace " + workspaceId + " has no registered bank account; "
            + "register one before reconciling, as there is no deployment-wide account to fall back on");
    }
}
