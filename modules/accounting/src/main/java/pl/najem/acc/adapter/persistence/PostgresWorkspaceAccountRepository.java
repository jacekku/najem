package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.WorkspaceAccountRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** {@link WorkspaceAccountRepository} over acc_workspace_account. */
@Repository
public class PostgresWorkspaceAccountRepository implements WorkspaceAccountRepository {

    private final JdbcTemplate jdbc;

    public PostgresWorkspaceAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Upsert on the workspace, so re-registering replaces. The <em>iban</em> unique index is not
     * touched by the conflict clause on purpose: registering an account another workspace already
     * holds must fail loudly rather than quietly move it.
     */
    @Override
    public void register(UUID workspaceId, String iban, LocalDate registeredOn) {
        jdbc.update("""
            insert into acc_workspace_account(workspace_id, iban, registered_on) values (?,?,?)
            on conflict (workspace_id) do update set iban = excluded.iban,
                                                     registered_on = excluded.registered_on
            """, workspaceId, iban, registeredOn);
    }

    @Override
    public Optional<String> ibanOf(UUID workspaceId) {
        return jdbc.query("select iban from acc_workspace_account where workspace_id = ?",
            (rs, i) -> rs.getString(1), workspaceId).stream().findFirst();
    }
}
