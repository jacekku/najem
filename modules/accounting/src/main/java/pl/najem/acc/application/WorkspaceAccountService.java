package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
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
 */
@Service
@Transactional
public class WorkspaceAccountService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WorkspaceAccountService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
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
        jdbc.update("""
            insert into acc_workspace_account(workspace_id, iban, registered_on) values (?,?,?)
            on conflict (workspace_id) do update set iban = excluded.iban,
                                                     registered_on = excluded.registered_on
            """, workspaceId, iban.strip(), LocalDate.now(clock));
    }
}
