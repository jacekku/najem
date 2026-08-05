package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.DepositRepository;
import pl.najem.acc.application.DepositToCharge;
import pl.najem.acc.domain.HeldDeposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** {@link DepositRepository} over acc_deposit and acc_deposit_deduction. */
@Repository
public class PostgresDepositRepository implements DepositRepository {

    private final JdbcTemplate jdbc;

    public PostgresDepositRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void charge(UUID workspaceId, UUID tenancyId, DepositToCharge deposit) {
        jdbc.update("""
            insert into acc_deposit(deposit_id, workspace_id, tenancy_id, charge_id, legal_form,
                                    nominal_amount, rent_at_charge, multiplier, state, charged_on)
            values (?,?,?,?,?,?,?,?, 'charged', ?)
            """, deposit.depositId(), workspaceId, tenancyId, deposit.invoiceId(),
            deposit.legalForm(), deposit.amount(), deposit.rentAtCharge(), deposit.multiplier(),
            deposit.chargedOn());
    }

    /**
     * {@code unpaid} comes from the invoice rather than from the deposit row: whether the money
     * arrived is a fact about the charge, and asking the charge is what keeps the two from
     * disagreeing.
     */
    @Override
    public Optional<HeldDeposit> find(UUID workspaceId, UUID tenancyId) {
        return jdbc.query("""
            select d.deposit_id, d.multiplier, d.nominal_amount, d.state,
                   c.amount - c.allocated_amount as unpaid
            from acc_deposit d join acc_charge c on c.charge_id = d.charge_id
            where d.workspace_id = ? and d.tenancy_id = ?
            """, (rs, i) -> new HeldDeposit(rs.getObject(1, UUID.class), rs.getBigDecimal(2),
                rs.getBigDecimal(3), "settled".equals(rs.getString(4)), rs.getBigDecimal(5)),
            workspaceId, tenancyId).stream().findFirst();
    }

    @Override
    public void deduct(UUID workspaceId, UUID depositId, UUID invoiceId, BigDecimal amount,
                       LocalDate deductedOn) {
        jdbc.update("""
            insert into acc_deposit_deduction(deduction_id, workspace_id, deposit_id, charge_id,
                                              amount, deducted_on)
            values (?,?,?,?,?,?)
            """, UUID.randomUUID(), workspaceId, depositId, invoiceId, amount, deductedOn);
    }

    @Override
    public void settle(UUID workspaceId, UUID depositId, LocalDate returnedOn,
                       BigDecimal rentAtReturn, BigDecimal valorized, BigDecimal deducted,
                       BigDecimal returned) {
        jdbc.update("""
            update acc_deposit
            set state = 'settled', settled_on = ?, rent_at_return = ?, valorized_amount = ?,
                deducted_amount = ?, returned_amount = ?
            where workspace_id = ? and deposit_id = ?
            """, returnedOn, rentAtReturn, valorized, deducted, returned, workspaceId, depositId);
    }
}
