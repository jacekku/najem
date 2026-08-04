package pl.najem.contacts.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contacts.domain.InterestRegistered;
import pl.najem.contacts.domain.InterestWithdrawn;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class InterestService {

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final ContactDirectory directory;

    public InterestService(EventStore store, JdbcTemplate jdbc, ContactDirectory directory) {
        this.store = store;
        this.jdbc = jdbc;
        this.directory = directory;
    }

    public UUID register(UUID workspaceId, UUID contactId, UUID unitId,
                         BigDecimal willingToPay, LocalDate desiredStart) {
        directory.requireIn(workspaceId, contactId);
        UUID interestId = UUID.randomUUID();
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestRegistered(workspaceId, interestId, contactId, unitId,
                willingToPay, desiredStart)), List.of());
        jdbc.update("""
            insert into contacts_interest(interest_id, workspace_id, contact_id, unit_id,
                                          willing_to_pay, desired_start, status)
            values (?,?,?,?,?,?, 'active')
            """, interestId, workspaceId, contactId, unitId, willingToPay, desiredStart);
        return interestId;
    }

    /**
     * The lookup is the workspace gate: it names the workspace, so a foreign or unknown interest
     * finds nothing and the command is refused before anything is appended.
     * <p>
     * It used to use {@code queryForObject}, which throws {@code EmptyResultDataAccessException} on
     * no rows — safe, but a Spring data-access exception reaching the edge is a <b>500</b>, so an
     * ordinary "not yours" was reported as the server having broken. A caller cannot tell a bad id
     * from an outage, and an alert on 5xx fires for routine traffic.
     */
    public void withdraw(UUID workspaceId, UUID interestId, LocalDate withdrawnOn) {
        UUID contactId = jdbc.queryForList(
            "select contact_id from contacts_interest where workspace_id = ? and interest_id = ?",
            UUID.class, workspaceId, interestId)
            .stream().findFirst()
            .orElseThrow(() -> new NoSuchInterestException(interestId));
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestWithdrawn(workspaceId, interestId, contactId, withdrawnOn)), List.of());
        jdbc.update("update contacts_interest set status = 'withdrawn' where workspace_id = ? and interest_id = ?",
            workspaceId, interestId);
    }

    public List<Interest> forUnit(UUID workspaceId, UUID unitId) {
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

    public List<Object> eventsFor(UUID contactId) {
        return List.copyOf(store.load(contactId, "Contact").events());
    }
}
