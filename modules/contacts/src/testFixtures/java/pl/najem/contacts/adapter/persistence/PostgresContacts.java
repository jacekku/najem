package pl.najem.contacts.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.ContactService;
import pl.najem.contacts.application.InterestService;
import pl.najem.contacts.application.RetentionService;
import pl.najem.eventstore.EventStore;

/**
 * Assembles the contacts services onto a database, for the tests that do not boot Spring.
 *
 * <p>The counterpart of {@code PostgresAccounting} and {@code PostgresPropertyManagement}, written
 * for the same reason (rule 6): the wiring is knowledge about which implementation to use, so it
 * belongs on the adapter side of the boundary rather than in a convenience constructor on a service,
 * which would have the application layer naming the adapters that implement its own ports.
 *
 * <p>A fixtures source set rather than {@code src/main}, because production has Spring and no
 * deployment calls any of this.
 *
 * <p>No clock parameter, unlike accounting's. Nothing here asks what today is: every date in this
 * module — {@code setOn}, {@code releasedOn}, {@code erasedOn}, {@code asOf} — arrives as an
 * argument from the caller, which is why {@code WallClockTest} can assert the module never reads
 * one. If that ever changes, this class gains the parameter rather than a default.
 */
public final class PostgresContacts {

    private PostgresContacts() {
    }

    public static ContactDirectory directory(JdbcTemplate jdbc) {
        return new ContactDirectory(new PostgresContactRepository(jdbc));
    }

    public static RetentionService retentionService(EventStore store, JdbcTemplate jdbc) {
        return new RetentionService(store, new PostgresRetentionHoldRepository(jdbc),
            new PostgresErasureDueQuery(jdbc), directory(jdbc));
    }

    public static ContactService contactService(EventStore store, JdbcTemplate jdbc) {
        return new ContactService(store, new PostgresContactRepository(jdbc),
            new PostgresInterestRepository(jdbc), retentionService(store, jdbc), directory(jdbc));
    }

    public static InterestService interestService(EventStore store, JdbcTemplate jdbc) {
        return new InterestService(store, new PostgresInterestRepository(jdbc), directory(jdbc));
    }
}
