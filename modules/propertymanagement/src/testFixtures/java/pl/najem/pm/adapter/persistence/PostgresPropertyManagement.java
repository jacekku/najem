package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.application.AttentionListsService;
import pl.najem.pm.application.ChecklistService;
import pl.najem.pm.application.ComplianceService;
import pl.najem.pm.application.EndOfTenancyProcess;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.application.ProcessDueRepository;
import pl.najem.pm.application.RentChangeProcess;
import pl.najem.pm.application.RepairService;
import pl.najem.pm.application.TenancyBoardProjection;
import pl.najem.pm.application.TenancyService;
import pl.najem.pm.application.TenancyStartProcess;

import java.time.Clock;

/**
 * Assembles the property-management services onto a database, for the tests that do not boot Spring.
 *
 * <p>The counterpart of {@code PostgresAccounting}, and written for the same reason (rule 6): the
 * wiring is knowledge about which implementation to use, so it belongs on the adapter side of the
 * boundary rather than in a convenience constructor on a service, which would have the application
 * layer naming the adapters that implement its own ports.
 *
 * <p>A fixtures source set rather than one module's private helper, because reporting's tripwires
 * drive PM's real services and need the same wiring — and rather than {@code src/main}, because
 * production has Spring and no deployment calls any of this.
 *
 * <p>It was worth writing the day PM ran out of ports to extract. Twenty test files were newing up
 * four adapters each to get "a working PM", so adding one constructor argument to
 * {@code TenancyService} meant a sweep across two modules to change nothing. That cost is the
 * argument: a port that is expensive to add is a port that does not get added.
 *
 * <p>The clock here is the system default. A caller whose assertions depend on today — anything
 * driving a sweep — should build the process manager itself with a fixed clock, or use
 * {@code runDue}, which takes the date as an argument precisely so the wall clock stays out of it.
 */
public final class PostgresPropertyManagement {

    private PostgresPropertyManagement() {
    }

    public static PortfolioService portfolioService(EventStore store, JdbcTemplate jdbc) {
        return new PortfolioService(store, new PostgresPortfolioProjection(jdbc));
    }

    public static TenancyService tenancyService(EventStore store, JdbcTemplate jdbc) {
        return new TenancyService(store, new PostgresTenancyProjection(jdbc), processDue(jdbc));
    }

    /** No adapters at all — its store is the event stream. Here so callers need one import. */
    public static ChecklistService checklistService(EventStore store) {
        return new ChecklistService(store);
    }

    public static RepairService repairService(EventStore store, JdbcTemplate jdbc) {
        return new RepairService(store, new PostgresRepairProjection(jdbc),
            portfolioService(store, jdbc));
    }

    public static ComplianceService complianceService(EventStore store, JdbcTemplate jdbc) {
        return new ComplianceService(store, new PostgresInspectionProjection(jdbc),
            new PostgresOverdueInspectionQuery(jdbc));
    }

    /**
     * The register's read port. Returned as the port and not the adapter, so a test that drives it
     * is testing what a screen holds rather than what happens to implement it today.
     */
    public static TenancyBoardProjection tenancyBoard(JdbcTemplate jdbc) {
        return new PostgresTenancyBoardProjection(jdbc);
    }

    public static AttentionListsService attentionListsService(JdbcTemplate jdbc) {
        return new AttentionListsService(new PostgresAttentionListsProjection(jdbc),
            new PostgresOpenRepairQuery(jdbc));
    }

    /**
     * The timer store, exposed because a test that arms or inspects a timer needs the same instance
     * the services write through — two adapters over one table would be two of nothing, but naming
     * it once is what keeps a test from reaching for the SQL.
     */
    public static ProcessDueRepository processDue(JdbcTemplate jdbc) {
        return new PostgresProcessDueRepository(jdbc);
    }

    /*
     * The process managers take the clock as a parameter and are not defaulted here. Their whole
     * job is "what is due today", so a fixture that picked the clock for a caller would be picking
     * the answer. Every existing test passes one explicitly.
     */

    public static TenancyStartProcess tenancyStartProcess(EventStore store, JdbcTemplate jdbc,
                                                          Clock clock) {
        return new TenancyStartProcess(processDue(jdbc), tenancyService(store, jdbc), clock);
    }

    public static RentChangeProcess rentChangeProcess(EventStore store, JdbcTemplate jdbc,
                                                      Clock clock) {
        return new RentChangeProcess(processDue(jdbc), tenancyService(store, jdbc), clock);
    }

    public static EndOfTenancyProcess endOfTenancyProcess(EventStore store, JdbcTemplate jdbc,
                                                          Clock clock) {
        return new EndOfTenancyProcess(processDue(jdbc), tenancyService(store, jdbc), clock);
    }
}
