package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.InvoiceRepository;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.Invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@link InvoiceRepository} over acc_charge. */
@Repository
public class PostgresInvoiceRepository implements InvoiceRepository {

    private final JdbcTemplate jdbc;

    public PostgresInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The workspace clause is the boundary: another agency's obligations are not visible here. */
    @Override
    public List<Invoice> openInvoices(UUID workspaceId, UUID tenancyId) {
        return jdbc.query("""
            select charge_id, component, due_date, amount - allocated_amount as owed
            from acc_charge
            where workspace_id = ? and tenancy_id = ? and active and amount > allocated_amount
            """, (rs, i) -> new Invoice(rs.getObject(1, UUID.class), Component.of(rs.getString(2)),
                rs.getDate(3).toLocalDate(), rs.getBigDecimal(4)), workspaceId, tenancyId);
    }

    @Override
    public void applyAllocation(UUID workspaceId, UUID invoiceId, BigDecimal amount) {
        jdbc.update("""
            update acc_charge
            set allocated_amount = allocated_amount + ?,
                allocated = allocated_amount + ? >= amount
            where workspace_id = ? and charge_id = ?
            """, amount, amount, workspaceId, invoiceId);
    }
}
