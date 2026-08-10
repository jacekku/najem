package pl.najem.contacts.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.contacts.application.InterestedParty;
import pl.najem.contacts.application.UnitInterestQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The join in one statement.
 *
 * <p>An inner join, deliberately: an interest whose person has been erased is not a row a screen
 * may show, and erasure deletes both — so the join being inner is the belt to that braces.
 *
 * <p>Both tables are filtered on the workspace. The interest side alone would be enough today
 * because interests carry the same workspace as their contact, but a join that scopes one side and
 * trusts the other is a scope that holds by coincidence.
 */
@Repository
public class PostgresUnitInterestQuery implements UnitInterestQuery {

    private final JdbcTemplate jdbc;

    public PostgresUnitInterestQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<InterestedParty> activeForUnit(UUID workspaceId, UUID unitId) {
        return jdbc.query("""
            select i.interest_id, i.contact_id, p.given_name, p.surname, p.email, p.phone,
                   i.willing_to_pay, i.desired_start
            from contacts_interest i
            join contacts_person p
              on p.contact_id = i.contact_id and p.workspace_id = i.workspace_id
            where i.workspace_id = ? and i.unit_id = ? and i.status = 'active'
            order by p.surname, p.given_name, i.interest_id
            """,
            (rs, i) -> new InterestedParty(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getBigDecimal(7), rs.getObject(8, LocalDate.class)),
            workspaceId, unitId);
    }
}
