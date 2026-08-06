package pl.najem.acc.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Which account a workspace's money arrives in.
 *
 * <p>Registering is deliberately an explicit act rather than a configuration value: the account
 * answers "whose money is this?", and there is no answer a deployment can give on a workspace's
 * behalf. Until a workspace registers one it cannot reconcile at all.
 *
 * <p>That last sentence is a rule, so it is one method rather than a query every caller repeats.
 * {@link #accountOf} refuses; nothing in this module may fall back to an account it was not given.
 */
@Service
@Transactional
public class WorkspaceAccountService {

    private final WorkspaceAccountRepository accounts;
    private final Clock clock;

    public WorkspaceAccountService(WorkspaceAccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Names the account this workspace's statements are fetched from, replacing any previous one.
     *
     * <p>Re-registering is allowed because agencies do change banks. Registering an account that
     * already belongs to another workspace is not, and fails on the unique index rather than here:
     * two workspaces sharing one account would each ingest every line in it, which is the defect
     * this table was created to remove.
     */
    public void register(UUID workspaceId, String iban) {
        if (iban == null || iban.isBlank()) {
            throw new IllegalArgumentException("an account registration needs an iban");
        }
        accounts.register(workspaceId, iban.strip(), LocalDate.now(clock));
    }

    /**
     * The account to fetch this workspace's statement from.
     *
     * <p>There is no fallback and there must not be one. Ingesting from a configured account and
     * handing every line to whichever workspace asked is how one transfer came to be suggested
     * against charges in two different agencies and, if both accepted, read as paid in both.
     */
    public String accountOf(UUID workspaceId) {
        return accounts.ibanOf(workspaceId)
            .orElseThrow(() -> new NoBankAccountRegisteredException(workspaceId));
    }
}
