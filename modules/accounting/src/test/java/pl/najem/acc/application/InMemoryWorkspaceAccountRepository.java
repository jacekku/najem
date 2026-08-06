package pl.najem.acc.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link WorkspaceAccountRepository} kept in a map.
 *
 * <p>It does <strong>not</strong> enforce one-workspace-per-account. The real constraint is a unique
 * index, and a check written here would be a different mechanism wearing the same name — it would
 * pass where two concurrent registrations pass the database's, and it would tempt someone to believe
 * the rule is tested when only the fake's version of it is. That claim belongs to
 * {@code WorkspaceBankAccountTest}, against Postgres.
 */
public class InMemoryWorkspaceAccountRepository implements WorkspaceAccountRepository {

    private record Registration(String iban, LocalDate registeredOn) {
    }

    private final Map<UUID, Registration> accounts = new HashMap<>();

    public LocalDate registeredOn(UUID workspaceId) {
        return accounts.get(workspaceId).registeredOn();
    }

    /** Replaces, as the upsert on workspace_id does. */
    @Override
    public void register(UUID workspaceId, String iban, LocalDate registeredOn) {
        accounts.put(workspaceId, new Registration(iban, registeredOn));
    }

    @Override
    public Optional<String> ibanOf(UUID workspaceId) {
        return Optional.ofNullable(accounts.get(workspaceId)).map(Registration::iban);
    }
}
