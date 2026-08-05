package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import pl.najem.acc.application.AccountingService;
import pl.najem.acc.application.AllocationService;
import pl.najem.acc.application.BoardService;
import pl.najem.acc.application.ReconciliationService;
import pl.najem.acc.application.SuspenseService;
import pl.najem.acc.application.WarningService;
import pl.najem.eventstore.EventStore;

import java.time.Clock;

/**
 * Assembles the accounting services onto a database, for tests and callers outside the container.
 *
 * <p>Spring does this assembly from the beans it scans. Everyone else used to do it in convenience
 * constructors on the services themselves, which meant the application layer naming the adapters
 * that implement its own ports — the dependency the ports exist to invert. The wiring is knowledge
 * about which implementation to use, so it belongs on this side of the boundary.
 *
 * <p>The clock here is the system default rather than the {@code Europe/Warsaw} bean the container
 * supplies. Callers whose assertions depend on today should build the services themselves and pass
 * a fixed clock rather than reaching for these.
 */
public final class PostgresAccounting {

    private PostgresAccounting() {
    }

    public static AllocationService allocationService(EventStore store, JdbcTemplate jdbc) {
        return new AllocationService(store, new PostgresPaymentRepository(jdbc),
            new PostgresInvoiceRepository(jdbc), new PostgresAccountingRepository(jdbc));
    }

    public static AccountingService accountingService(EventStore store, JdbcTemplate jdbc) {
        return new AccountingService(allocationService(store, jdbc),
            new BoardService(jdbc, Clock.systemDefaultZone()));
    }

    public static SuspenseService suspenseService(EventStore store, JdbcTemplate jdbc) {
        return new SuspenseService(store, jdbc, accountingService(store, jdbc));
    }

    public static ReconciliationService reconciliationService(EventStore store, JdbcTemplate jdbc) {
        return new ReconciliationService(jdbc, new WarningService(jdbc),
            accountingService(store, jdbc), Clock.systemDefaultZone());
    }
}
