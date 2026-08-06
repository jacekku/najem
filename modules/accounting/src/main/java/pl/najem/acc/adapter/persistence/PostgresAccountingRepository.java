package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.AccountingRepository;
import pl.najem.acc.domain.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** {@link AccountingRepository} over acc_allocation. */
@Repository
public class PostgresAccountingRepository implements AccountingRepository {

    private final JdbcTemplate jdbc;

    public PostgresAccountingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordAllocation(UUID allocationId, UUID workspaceId, UUID paymentId, UUID invoiceId,
                                 UUID tenancyId, Component component, BigDecimal amount,
                                 LocalDate dueDate) {
        jdbc.update("""
            insert into acc_allocation(allocation_id, workspace_id, payment_id, charge_id,
                                       tenancy_id, component, amount, allocated_on)
            values (?,?,?,?,?,?,?,?)
            """, allocationId, workspaceId, paymentId, invoiceId, tenancyId, component.wireName(),
            amount, dueDate);
    }

    @Override
    public java.util.List<pl.najem.acc.application.LiveAllocation> liveAllocations(UUID workspaceId,
                                                                                   UUID paymentId) {
        return jdbc.query("""
            select charge_id, tenancy_id, amount from acc_allocation
            where workspace_id = ? and payment_id = ? and not reversed
            order by charge_id
            """, (rs, i) -> new pl.najem.acc.application.LiveAllocation(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getBigDecimal(3)), workspaceId, paymentId);
    }

    @Override
    public void markReversed(UUID workspaceId, UUID paymentId) {
        jdbc.update("""
            update acc_allocation set reversed = true
            where workspace_id = ? and payment_id = ? and not reversed
            """, workspaceId, paymentId);
    }
}
