package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.PayerAccountRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@link PayerAccountRepository} over acc_payer_account. */
@Repository
public class PostgresPayerAccountRepository implements PayerAccountRepository {

    private final JdbcTemplate jdbc;

    public PostgresPayerAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ordered explicitly. The caller names one of these in a warning a manager reads, and an
     * unordered query would word the same warning differently on two runs over the same rows.
     */
    @Override
    public List<UUID> tenanciesPaidFrom(UUID workspaceId, String iban) {
        return jdbc.query("""
            select tenancy_id from acc_payer_account
            where workspace_id = ? and counterparty_iban = ?
            order by learned_on, tenancy_id
            """, (rs, i) -> rs.getObject(1, UUID.class), workspaceId, iban);
    }

    @Override
    public void learn(UUID workspaceId, String iban, UUID tenancyId, UUID learnedFrom,
                      LocalDate learnedOn) {
        jdbc.update("""
            insert into acc_payer_account(workspace_id, counterparty_iban, tenancy_id, learned_from, learned_on)
            values (?,?,?,?,?)
            on conflict (workspace_id, counterparty_iban, tenancy_id)
            do update set learned_from = excluded.learned_from, learned_on = excluded.learned_on
            """, workspaceId, iban, tenancyId, learnedFrom, learnedOn);
    }
}
