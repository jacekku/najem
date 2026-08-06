package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.SuggestionQuery;

import java.util.List;
import java.util.UUID;

/** {@link SuggestionQuery} over acc_suggestion joined to the payment and charge it names. */
@Repository
public class PostgresSuggestionQuery implements SuggestionQuery {

    private final JdbcTemplate jdbc;

    public PostgresSuggestionQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> forWorkspace(UUID workspaceId) {
        return jdbc.query("""
            select s.payment_id, s.charge_id, c.tenancy_id, s.tier,
                   p.amount, c.amount, c.amount - c.allocated_amount,
                   p.booking_date, c.due_date, c.component,
                   p.title, c.payment_reference, p.counterparty_name, p.counterparty_iban
            from acc_suggestion s
            join acc_payment p on p.payment_id = s.payment_id
            join acc_charge  c on c.charge_id  = s.charge_id
            where s.workspace_id = ?
            order by s.tier, p.booking_date, s.payment_id
            """, (rs, i) -> new Row(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getInt(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                rs.getDate(8).toLocalDate(), rs.getDate(9).toLocalDate(), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14)),
            workspaceId);
    }
}
