package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.InvoiceMatching;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** {@link InvoiceMatching} over acc_charge. */
@Repository
public class PostgresInvoiceMatching implements InvoiceMatching {

    private final JdbcTemplate jdbc;

    public PostgresInvoiceMatching(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> byExactReferenceAndAmount(UUID workspaceId, String reference,
                                                    BigDecimal amount) {
        return first("""
            select charge_id from acc_charge
            where workspace_id = ? and payment_reference = ? and amount = ? and not allocated and active
            order by due_date limit 1
            """, workspaceId, reference, amount);
    }

    /**
     * The stripping is done in SQL on the charge's side and in Java on the payer's, because only one
     * of the two is a column. Both discard everything but letters and digits, and if they ever
     * stopped agreeing tier 2 would silently match nothing.
     */
    @Override
    public Optional<UUID> byReferenceWithin(UUID workspaceId, String normalisedTitle) {
        return first("""
            select charge_id from acc_charge
            where workspace_id = ? and not allocated and active
              and regexp_replace(upper(payment_reference), '[^A-Z0-9]', '', 'g') <> ''
              and position(regexp_replace(upper(payment_reference), '[^A-Z0-9]', '', 'g') in ?) > 0
            order by length(regexp_replace(upper(payment_reference), '[^A-Z0-9]', '', 'g')) desc,
                     due_date
            limit 1
            """, workspaceId, normalisedTitle);
    }

    @Override
    public Optional<UUID> oldestOpenOf(UUID workspaceId, UUID tenancyId) {
        return first("""
            select charge_id from acc_charge
            where workspace_id = ? and tenancy_id = ? and not allocated and active
            order by due_date limit 1
            """, workspaceId, tenancyId);
    }

    private Optional<UUID> first(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> rs.getObject(1, UUID.class), args).stream().findFirst();
    }
}
