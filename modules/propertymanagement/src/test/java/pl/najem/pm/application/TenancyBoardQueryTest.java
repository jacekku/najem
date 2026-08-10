package pl.najem.pm.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.contracts.events.TenancyEndedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.adapter.persistence.PostgresPropertyManagement;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Term;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenancyBoardProjection} against Postgres — the authoritative tier.
 * {@code TenancyRegisterTest} mirrors these questions in milliseconds, and when the two disagree
 * <strong>this one is right</strong> (rule 16).
 *
 * <p>It exists because the mechanism this port is built on lives in the statement and nowhere else
 * (rule 15). The {@code array_agg} that collapses a tenancy's parties into one column, the two
 * joins that decide whether a row appears at all, the {@code end_reason is null or <> } predicate
 * spanning a column that is null for most rows — none of those are Java, so an in-memory double can
 * restate what they are supposed to mean but can never catch them being wrong.
 */
@Testcontainers
@Tag("integration")
class TenancyBoardQueryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static PortfolioService portfolio;
    static TenancyService tenancies;
    static TenancyBoardProjection board;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(),
            pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        registry.register(TenancyActivatedEvent.class);
        registry.register(TenancyEndedEvent.class);
        var store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        portfolio = PostgresPropertyManagement.portfolioService(store, jdbc);
        tenancies = PostgresPropertyManagement.tenancyService(store, jdbc);
        board = PostgresPropertyManagement.tenancyBoard(jdbc);
    }

    /**
     * The array_agg, end to end: two tenants on one tenancy come back as one row carrying both ids,
     * not as two rows. A join in place of the sub-select would have produced two.
     */
    @Test
    void atenancyWithTwoTenantsIsOneRowCarryingBothIds() {
        var agency = UUID.randomUUID();
        var anna = UUID.randomUUID();
        var karol = UUID.randomUUID();
        var tenancyId = reserve(agency, unitIn(agency, "Krucza 3", "M1"), List.of(anna, karol),
            List.of());

        assertThat(board.forWorkspace(agency))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.tenancyId()).isEqualTo(tenancyId);
                assertThat(row.tenantContactIds()).containsExactlyInAnyOrder(anna, karol);
                assertThat(row.propertyAddress()).isEqualTo("Krucza 3");
                assertThat(row.unitName()).isEqualTo("M1");
                assertThat(row.monthlyTotal()).isEqualByComparingTo("2500");
            });
    }

    /** The role column separating two kinds of party in one table, read by the real statement. */
    @Test
    void aguarantorIsStoredButIsNotReturnedAsATenant() {
        var agency = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        var guarantor = UUID.randomUUID();
        var tenancyId = reserve(agency, unitIn(agency, "Krucza 5", "M1"), List.of(tenant),
            List.of(guarantor));

        assertThat(board.forWorkspace(agency))
            .singleElement()
            .satisfies(row -> assertThat(row.tenantContactIds()).containsExactly(tenant));
        assertThat(jdbc.queryForObject(
            "select count(*) from pm_tenancy_party where tenancy_id = ? and role = 'GUARANTOR'",
            Integer.class, tenancyId)).isEqualTo(1);
    }

    /** The two writes that had no projection at all before V20260810120000, against the real delete. */
    @Test
    void addingAndRemovingATenantBothReachTheTable() {
        var agency = UUID.randomUUID();
        var anna = UUID.randomUUID();
        var karol = UUID.randomUUID();
        var tenancyId = reserve(agency, unitIn(agency, "Krucza 7", "M1"), List.of(anna), List.of());

        tenancies.addTenant(agency, tenancyId, karol);
        assertThat(board.forWorkspace(agency)).singleElement()
            .satisfies(row -> assertThat(row.tenantContactIds()).containsExactlyInAnyOrder(anna, karol));

        tenancies.removeTenant(agency, tenancyId, anna);
        assertThat(board.forWorkspace(agency)).singleElement()
            .satisfies(row -> assertThat(row.tenantContactIds()).containsExactly(karol));
    }

    /**
     * The three-way distinction the register's {@code where} clause exists to draw, in one test so
     * the boundary between the cases is what is asserted rather than any one of them: an expired
     * tenancy stays, a cancelled reservation goes, an annulled tenancy goes.
     *
     * <p>Together in one method for the reason {@code ReportsScreenTest}'s tab test gives — a change
     * that excluded every ended row would satisfy a test that only knew about annulment, and a
     * change that excluded nothing would satisfy one that only knew about expiry.
     */
    @Test
    void expiredStaysWhileCancelledAndAnnulledBothLeave() {
        var agency = UUID.randomUUID();
        var expired = endedTenancy(agency, "Wilcza 1", EndReason.AGREEMENT_EXPIRY);
        var annulled = endedTenancy(agency, "Wilcza 2", EndReason.ERROR_ANNULLED);
        var cancelled = reserve(agency, unitIn(agency, "Wilcza 3", "M1"),
            List.of(UUID.randomUUID()), List.of());
        tenancies.cancelReservation(agency, cancelled, "najemca się rozmyślił");

        assertThat(board.forWorkspace(agency))
            .extracting(TenancyBoardRow::tenancyId)
            .containsExactly(expired)
            .doesNotContain(annulled, cancelled);
        assertThat(board.forWorkspace(agency)).singleElement()
            .satisfies(row -> assertThat(row.state()).isEqualTo(Tenancy.State.ENDED));
    }

    /**
     * A tenancy with no party rows at all — every row written before V20260810120000 — comes back with an empty
     * list rather than blowing up. {@code array_agg} returns SQL null for an empty group, and a
     * reader that unwrapped it without checking would take the whole register down for one row.
     */
    @Test
    void atenancyWithNoPartyRowsReturnsAnEmptyListNotAFailure() {
        var agency = UUID.randomUUID();
        var tenancyId = reserve(agency, unitIn(agency, "Hoża 9", "M1"), List.of(UUID.randomUUID()),
            List.of());
        jdbc.update("delete from pm_tenancy_party where tenancy_id = ?", tenancyId);

        assertThat(board.forWorkspace(agency))
            .singleElement()
            .satisfies(row -> assertThat(row.tenantContactIds()).isEmpty());
    }

    /**
     * The joins are INNER, and that is a decision rather than a default: a tenancy the portfolio
     * cannot place drops out entirely instead of rendering with a null address.
     *
     * <p>Added because mutating the property join to a LEFT join left every other test in this class
     * green. Both tiers claimed the inner-join semantics in their javadoc — {@code InMemoryTenancies}
     * models it by flat-mapping over an Optional — and nothing asserted it, so the two would have
     * silently disagreed the first time a row went missing. That is exactly the disagreement rule 16
     * says to settle here rather than in the fast tier.
     */
    @Test
    void atenancyWhosePropertyRowIsMissingDropsOutRatherThanRenderingBlank() {
        var agency = UUID.randomUUID();
        var unitId = unitIn(agency, "Foksal 2", "M1");
        reserve(agency, unitId, List.of(UUID.randomUUID()), List.of());
        assertThat(board.forWorkspace(agency)).hasSize(1);

        jdbc.update("delete from pm_property where property_id = "
            + "(select property_id from pm_unit where unit_id = ?)", unitId);

        assertThat(board.forWorkspace(agency)).isEmpty();
    }

    /** Workspace is the hard boundary: a filter in the statement, and an empty answer. */
    @Test
    void anotherAgencySeesNothing() {
        var agency = UUID.randomUUID();
        reserve(agency, unitIn(agency, "Hoża 11", "M1"), List.of(UUID.randomUUID()), List.of());

        assertThat(board.forWorkspace(UUID.randomUUID())).isEmpty();
    }

    private UUID endedTenancy(UUID agency, String address, EndReason reason) {
        var tenancyId = reserve(agency, unitIn(agency, address, "M1"), List.of(UUID.randomUUID()),
            List.of());
        tenancies.activate(agency, tenancyId, LocalDate.of(2026, 9, 1));
        tenancies.end(agency, tenancyId, new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 8, 31), reason, null, true));
        return tenancyId;
    }

    private static UUID unitIn(UUID workspaceId, String address, String name) {
        var propertyId = portfolio.createProperty(workspaceId, address,
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        return portfolio.addUnit(workspaceId, propertyId, name, new BigDecimal("2500"));
    }

    private static UUID reserve(UUID agency, UUID unitId, List<UUID> tenants, List<UUID> guarantors) {
        return tenancies.reserve(agency, new ReserveTenancy(null, null, unitId, tenants, guarantors,
            LocalDate.of(2026, 9, 1), new Term.FixedTerm(LocalDate.of(2027, 8, 31)),
            LegalForm.ZWYKLY, new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
    }
}
