package pl.najem.contacts.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.contacts.application.Interest;
import pl.najem.contacts.application.InterestRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresInterestRepository implements InterestRepository {

    private final JdbcTemplate jdbc;

    public PostgresInterestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID interestId, UUID workspaceId, UUID contactId, UUID unitId,
                       BigDecimal willingToPay, LocalDate desiredStart) {
        jdbc.update("""
            insert into contacts_interest(interest_id, workspace_id, contact_id, unit_id,
                                          willing_to_pay, desired_start, status)
            values (?,?,?,?,?,?, 'active')
            """, interestId, workspaceId, contactId, unitId, willingToPay, desiredStart);
    }

    @Override
    public Optional<Interest> find(UUID workspaceId, UUID interestId) {
        return jdbc.query("""
            select interest_id, contact_id, unit_id, willing_to_pay, desired_start, status
            from contacts_interest where workspace_id = ? and interest_id = ?
            """,
            (rs, i) -> new Interest(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getBigDecimal(4),
                rs.getObject(5, LocalDate.class), rs.getString(6)),
            workspaceId, interestId)
            .stream().findFirst();
    }

    @Override
    public void withdraw(UUID workspaceId, UUID interestId) {
        jdbc.update("update contacts_interest set status = 'withdrawn' where workspace_id = ? and interest_id = ?",
            workspaceId, interestId);
    }

    @Override
    public void convert(UUID workspaceId, UUID interestId, UUID tenancyId) {
        jdbc.update("""
            update contacts_interest set status = 'converted', converted_to_tenancy_id = ?
            where workspace_id = ? and interest_id = ?
            """, tenancyId, workspaceId, interestId);
    }

    @Override
    public List<Interest> activeForUnit(UUID workspaceId, UUID unitId) {
        return jdbc.query("""
            select interest_id, contact_id, unit_id, willing_to_pay, desired_start, status
            from contacts_interest
            where workspace_id = ? and unit_id = ? and status = 'active' order by interest_id
            """,
            (rs, i) -> new Interest(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getBigDecimal(4),
                rs.getObject(5, LocalDate.class), rs.getString(6)),
            workspaceId, unitId);
    }

    @Override
    public void deleteAllFor(UUID workspaceId, UUID contactId) {
        jdbc.update("delete from contacts_interest where workspace_id = ? and contact_id = ?",
            workspaceId, contactId);
    }
}
