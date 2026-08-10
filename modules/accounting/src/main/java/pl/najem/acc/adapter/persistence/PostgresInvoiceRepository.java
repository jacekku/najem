package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.InvoiceRepository;
import pl.najem.acc.application.InvoiceToPost;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.CreditNoteIssued;
import pl.najem.acc.domain.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link InvoiceRepository} over acc_charge and acc_credit_note. */
@Repository
public class PostgresInvoiceRepository implements InvoiceRepository {

    private final JdbcTemplate jdbc;

    public PostgresInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One batch rather than a statement per line: a month is written or it is not. */
    @Override
    public void post(UUID workspaceId, UUID tenancyId, List<InvoiceToPost> invoices,
                     LocalDate dueDate, String paymentReference) {
        jdbc.batchUpdate("""
            insert into acc_charge(charge_id, workspace_id, tenancy_id, component, amount, due_date, payment_reference)
            values (?,?,?,?,?,?,?)
            """, invoices.stream()
            .map(invoice -> new Object[] {invoice.invoiceId(), workspaceId, tenancyId,
                invoice.component().wireName(), invoice.amount(), dueDate, paymentReference})
            .toList());
    }

    /** The workspace clause is the boundary: another agency's obligations are not visible here. */
    @Override
    public List<Invoice> openInvoices(UUID workspaceId, UUID tenancyId) {
        return jdbc.query("""
            select charge_id, tenancy_id, component, due_date, amount, amount - allocated_amount, allocated
            from acc_charge
            where workspace_id = ? and tenancy_id = ? and active and amount > allocated_amount
            """, PostgresInvoiceRepository::invoice, workspaceId, tenancyId);
    }

    /**
     * The {@code where} clause is the one above, character for character, and the port's javadoc
     * says why that matters. {@code sum} over a filtered set never returns null here, because the
     * filter is what puts the group in the result at all.
     */
    @Override
    public Map<UUID, BigDecimal> outstandingByTenancy(UUID workspaceId) {
        var totals = new LinkedHashMap<UUID, BigDecimal>();
        jdbc.query("""
            select tenancy_id, sum(amount - allocated_amount) as outstanding
            from acc_charge
            where workspace_id = ? and active and amount > allocated_amount
            group by tenancy_id
            """,
            rs -> {
                totals.put(rs.getObject("tenancy_id", UUID.class),
                    rs.getBigDecimal("outstanding"));
            },
            workspaceId);
        return totals;
    }

    @Override
    public Optional<Invoice> find(UUID workspaceId, UUID invoiceId) {
        return jdbc.query("""
            select charge_id, tenancy_id, component, due_date, amount, amount - allocated_amount, allocated
            from acc_charge
            where workspace_id = ? and charge_id = ?
            """, PostgresInvoiceRepository::invoice, workspaceId, invoiceId).stream().findFirst();
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

    @Override
    public void unapplyAllocation(UUID workspaceId, UUID invoiceId, BigDecimal amount) {
        jdbc.update("""
            update acc_charge
            set allocated_amount = greatest(allocated_amount - ?, 0), allocated = false
            where workspace_id = ? and charge_id = ?
            """, amount, workspaceId, invoiceId);
    }

    @Override
    public void withdraw(UUID workspaceId, UUID invoiceId) {
        jdbc.update("""
            update acc_charge set active = false where workspace_id = ? and charge_id = ?
            """, workspaceId, invoiceId);
    }

    @Override
    public void recordCreditNote(UUID workspaceId, CreditNoteIssued note) {
        jdbc.update("""
            insert into acc_credit_note(credit_note_id, workspace_id, charge_id, tenancy_id, amount, reason, issued_on)
            values (?,?,?,?,?,?,?)
            """, note.creditNoteId(), workspaceId, note.chargeId(), note.tenancyId(), note.amount(),
            note.reason(), note.issuedOn());
    }

    /**
     * The latest rent line that had fallen due by then — the contractual figure, not a sum of what
     * is outstanding.
     *
     * <p>Withdrawn lines are <em>not</em> excluded, which is how this has always read. A rent line
     * deactivated as billed in error still answers "what was the rent", and valorization would then
     * be based on a charge the agency has taken back. Left alone here because a refactoring is not
     * where behaviour changes.
     */
    @Override
    public BigDecimal rentInForceOn(UUID workspaceId, UUID tenancyId, LocalDate asOf) {
        return jdbc.query("""
            select amount from acc_charge
            where workspace_id = ? and tenancy_id = ? and component = 'rent' and due_date <= ?
            order by due_date desc limit 1
            """, (rs, i) -> rs.getBigDecimal(1), workspaceId, tenancyId, asOf)
            .stream().findFirst().orElse(BigDecimal.ZERO);
    }

    private static Invoice invoice(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Invoice(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            Component.of(rs.getString(3)), rs.getDate(4).toLocalDate(), rs.getBigDecimal(5),
            rs.getBigDecimal(6), rs.getBoolean(7));
    }
}
