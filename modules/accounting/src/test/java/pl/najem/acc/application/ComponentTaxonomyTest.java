package pl.najem.acc.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.WorkspaceContext;
import pl.najem.acc.domain.Component;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract's wording is the switch: a tenancy whose contract splits the monthly total into
 * czynsz / opłaty administracyjne / zaliczki na media is charged per component, and one that does
 * not is collapsed into a single `rent` line — fully taxable and fully valorizable.
 */
@Testcontainers
class ComponentTaxonomyTest {

    private static final UUID WS = WorkspaceContext.DEV_WORKSPACE_ID;
    private static final LocalDate DUE = LocalDate.of(2026, 10, 10);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static LedgerService ledger;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        ledger = new LedgerService(store, jdbc, new WarningService(jdbc));
    }

    @Test
    void contractWithoutASplitCollapsesTheWholeTotalIntoRent() {
        var tenancyId = UUID.randomUUID();

        var posted = ledger.postMonthlyCharges(WS, tenancyId,
            MonthlyBreakdown.unsplit(new BigDecimal("3000")), DUE, "NAJEM/CT1/2026");

        assertThat(componentsOf(tenancyId)).containsExactly("rent");
        assertThat(amountOf(tenancyId, "rent")).isEqualByComparingTo("3000");
        assertThat(posted.warnings()).anySatisfy(w -> assertThat(w.detail()).contains("no contractual split"));
    }

    @Test
    void contractWithASplitChargesEachComponentSeparately() {
        var tenancyId = UUID.randomUUID();

        var posted = ledger.postMonthlyCharges(WS, tenancyId,
            MonthlyBreakdown.split(new BigDecimal("3000"), new BigDecimal("2400"),
                new BigDecimal("300"), new BigDecimal("300")),
            DUE, "NAJEM/CT2/2026");

        assertThat(componentsOf(tenancyId)).containsExactlyInAnyOrder("rent", "adminFee", "mediaAdvance");
        assertThat(amountOf(tenancyId, "rent")).isEqualByComparingTo("2400");
        assertThat(amountOf(tenancyId, "mediaAdvance")).isEqualByComparingTo("300");
        assertThat(posted.warnings()).noneSatisfy(w -> assertThat(w.detail()).contains("no contractual split"));
    }

    /**
     * The distinction the explicit flag exists for: a split contract that happens to carry no admin
     * fee is NOT a collapsed contract, even though both end up with a rent line and no adminFee line.
     * Only the collapsed one drags media into the tax and valorization base.
     */
    @Test
    void aSplitWithoutAnAdminFeeIsNotACollapse() {
        var tenancyId = UUID.randomUUID();

        var posted = ledger.postMonthlyCharges(WS, tenancyId,
            MonthlyBreakdown.split(new BigDecimal("2800"), new BigDecimal("2500"),
                null, new BigDecimal("300")),
            DUE, "NAJEM/CT3/2026");

        assertThat(componentsOf(tenancyId)).containsExactlyInAnyOrder("rent", "mediaAdvance");
        assertThat(posted.warnings()).noneSatisfy(w -> assertThat(w.detail()).contains("no contractual split"));
    }

    @Test
    void aBreakdownThatDoesNotSumToTheAgreedTotalWarnsAndChargesTheBreakdown() {
        var tenancyId = UUID.randomUUID();

        var posted = ledger.postMonthlyCharges(WS, tenancyId,
            MonthlyBreakdown.split(new BigDecimal("3000"), new BigDecimal("2400"),
                new BigDecimal("300"), new BigDecimal("200")),
            DUE, "NAJEM/CT4/2026");

        assertThat(posted.warnings()).anySatisfy(w -> assertThat(w.detail()).contains("does not sum"));
        assertThat(totalOf(tenancyId)).isEqualByComparingTo("2900");
    }

    /**
     * Valorization is computed on the czynsz component alone (DEPOSIT §1), so the ledger must be able
     * to answer "what is the rent component?" without re-reading the contract.
     */
    @Test
    void theValorizationBaseIsTheRentComponentAlone() {
        var tenancyId = UUID.randomUUID();
        ledger.postMonthlyCharges(WS, tenancyId,
            MonthlyBreakdown.split(new BigDecimal("3000"), new BigDecimal("2400"),
                new BigDecimal("300"), new BigDecimal("300")),
            DUE, "NAJEM/CT5/2026");

        assertThat(ledger.rentComponentAsOf(WS, tenancyId, DUE)).isEqualByComparingTo("2400");
    }

    @Test
    void everyChargedLineIsEventSourcedWithItsComponent() {
        var tenancyId = UUID.randomUUID();

        var posted = ledger.postMonthlyCharges(WS, tenancyId,
            MonthlyBreakdown.split(new BigDecimal("2700"), new BigDecimal("2500"),
                null, new BigDecimal("200")),
            DUE, "NAJEM/CT6/2026");

        assertThat(posted.chargeIds()).hasSize(2);
        assertThat(Component.of("mediaAdvance")).isEqualTo(Component.MEDIA_ADVANCE);
    }

    private static java.util.List<String> componentsOf(UUID tenancyId) {
        return jdbc.queryForList("select component from acc_charge where tenancy_id = ?",
            String.class, tenancyId);
    }

    private static BigDecimal amountOf(UUID tenancyId, String component) {
        return jdbc.queryForObject("select amount from acc_charge where tenancy_id = ? and component = ?",
            BigDecimal.class, tenancyId, component);
    }

    private static BigDecimal totalOf(UUID tenancyId) {
        return jdbc.queryForObject("select sum(amount) from acc_charge where tenancy_id = ?",
            BigDecimal.class, tenancyId);
    }
}
