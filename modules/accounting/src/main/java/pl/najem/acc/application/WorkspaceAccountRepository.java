package pl.najem.acc.application;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Which account each workspace's money arrives in.
 *
 * <p>One row per workspace, and one workspace per account. The second half is enforced by a unique
 * index rather than here: two workspaces sharing an account would each ingest every line in it, and
 * a check in application code is a check two concurrent registrations can both pass.
 */
public interface WorkspaceAccountRepository {

    /** Names the account, replacing any previous one — agencies do change banks. */
    void register(UUID workspaceId, String iban, LocalDate registeredOn);

    /** Empty when nobody has registered an account for that workspace, which is a real state. */
    Optional<String> ibanOf(UUID workspaceId);
}
