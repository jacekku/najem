package pl.najem.acc.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import pl.najem.acc.application.AccountingService;
import pl.najem.acc.application.AllocationService;
import pl.najem.acc.application.ArrearsBoardService;
import pl.najem.acc.application.CorrectionService;
import pl.najem.acc.application.BankStatementPort;
import pl.najem.acc.application.IngestionService;
import pl.najem.acc.application.MatchingPolicy;
import pl.najem.acc.application.WorkspaceAccountService;
import pl.najem.acc.application.DepositService;
import pl.najem.acc.application.InvoiceService;
import pl.najem.acc.application.ReconciliationService;
import pl.najem.acc.application.SuspenseService;
import pl.najem.acc.application.WarningService;
import pl.najem.eventstore.EventStore;

import java.time.Clock;

/**
 * Assembles the accounting services onto a database, for the tests that do not boot Spring.
 *
 * <p>Spring does this assembly from the beans it scans. Everyone else used to do it in convenience
 * constructors on the services themselves, which meant the application layer naming the adapters
 * that implement its own ports — the dependency the ports exist to invert. The wiring is knowledge
 * about which implementation to use, so it belongs on this side of the boundary.
 *
 * <p>A test fixture rather than production code, because production has Spring and nothing here is
 * ever called by the running application. It was in {@code src/main} first, which put a helper no
 * deployment uses into the jar every deployment ships. Reporting's tripwires drive accounting's
 * real services and need the same wiring, which is why this is a fixtures source set rather than
 * one module's private test helper.
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

    /**
     * The clock is a parameter here and nowhere else in this class, because the board is the one
     * service whose answer changes with the date on its own: the same charges are yellow today and
     * red tomorrow. A test that asserts a colour has to say when it is asking.
     */
    public static ArrearsBoardService arrearsBoardService(JdbcTemplate jdbc, Clock clock) {
        return new ArrearsBoardService(new PostgresInvoiceRepository(jdbc),
            new PostgresArrearsStandingProjection(jdbc), clock);
    }

    public static AccountingService accountingService(EventStore store, JdbcTemplate jdbc) {
        return new AccountingService(allocationService(store, jdbc),
            arrearsBoardService(jdbc, Clock.systemDefaultZone()));
    }

    public static InvoiceService invoiceService(EventStore store, JdbcTemplate jdbc,
                                              WarningService warnings) {
        return new InvoiceService(store, new PostgresInvoiceRepository(jdbc), warnings,
            arrearsBoardService(jdbc, Clock.systemDefaultZone()));
    }

    /**
     * The register the postings flag into. A parameter on the services that raise warnings, and
     * built here for the callers that only need one.
     */
    public static WarningService warningService(JdbcTemplate jdbc) {
        return new WarningService(new PostgresWarningRepository(jdbc));
    }

    public static DepositService depositService(EventStore store, JdbcTemplate jdbc) {
        return new DepositService(store, new PostgresDepositRepository(jdbc),
            new PostgresInvoiceRepository(jdbc), warningService(jdbc));
    }

    public static WorkspaceAccountService workspaceAccountService(JdbcTemplate jdbc) {
        return new WorkspaceAccountService(new PostgresWorkspaceAccountRepository(jdbc),
            Clock.systemDefaultZone());
    }

    /**
     * Ingestion needs the account register, because a workspace nobody registered an account for
     * cannot ingest and there is deliberately no fallback.
     */
    public static IngestionService ingestionService(BankStatementPort bank, EventStore store,
                                                    JdbcTemplate jdbc) {
        return ingestionService(bank, store, jdbc, MatchingPolicy.tierOneOnly());
    }

    public static IngestionService ingestionService(BankStatementPort bank, EventStore store,
                                                    JdbcTemplate jdbc, MatchingPolicy policy) {
        return new IngestionService(bank, store, new PostgresPaymentRepository(jdbc),
            new PostgresInvoiceMatching(jdbc), new PostgresPayerAccountRepository(jdbc),
            new PostgresSuggestionRepository(jdbc), workspaceAccountService(jdbc), policy,
            Clock.systemDefaultZone());
    }

    public static CorrectionService correctionService(EventStore store, JdbcTemplate jdbc) {
        return new CorrectionService(store, new PostgresPaymentRepository(jdbc),
            new PostgresInvoiceRepository(jdbc), new PostgresAccountingRepository(jdbc),
            accountingService(store, jdbc), arrearsBoardService(jdbc, Clock.systemDefaultZone()),
            Clock.systemDefaultZone());
    }

    public static SuspenseService suspenseService(EventStore store, JdbcTemplate jdbc) {
        return new SuspenseService(store, new PostgresPaymentRepository(jdbc),
            accountingService(store, jdbc));
    }

    public static ReconciliationService reconciliationService(EventStore store, JdbcTemplate jdbc) {
        return new ReconciliationService(new PostgresSuggestionRepository(jdbc),
            new PostgresInvoiceRepository(jdbc), new PostgresPaymentRepository(jdbc),
            new PostgresPayerAccountRepository(jdbc), warningService(jdbc),
            accountingService(store, jdbc), Clock.systemDefaultZone());
    }
}
