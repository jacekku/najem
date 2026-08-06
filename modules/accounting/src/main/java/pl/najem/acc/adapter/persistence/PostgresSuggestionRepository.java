package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.SuggestionRepository;
import pl.najem.acc.domain.MatchTier;

import java.util.Optional;
import java.util.UUID;

/** {@link SuggestionRepository} over acc_suggestion. */
@Repository
public class PostgresSuggestionRepository implements SuggestionRepository {

    private final JdbcTemplate jdbc;

    public PostgresSuggestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The workspace clause is the boundary: another agency's suggestion is simply not here. */
    @Override
    public Optional<UUID> suggestedInvoice(UUID workspaceId, UUID paymentId) {
        return jdbc.query("""
            select charge_id from acc_suggestion where workspace_id = ? and payment_id = ?
            """, (rs, i) -> rs.getObject(1, UUID.class), workspaceId, paymentId).stream().findFirst();
    }

    @Override
    public void suggest(UUID workspaceId, UUID paymentId, UUID invoiceId, MatchTier tier) {
        jdbc.update("""
            insert into acc_suggestion(payment_id, workspace_id, charge_id, tier) values (?,?,?,?)
            """, paymentId, workspaceId, invoiceId, tier.number());
    }
}
