package pl.najem.pm.application;

import pl.najem.pm.adapter.persistence.PostgresPropertyManagement;


import com.fasterxml.jackson.databind.ObjectMapper;
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
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Term;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class TenancyServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static PortfolioService portfolio;
    static TenancyService service;

    /** The acting agency for the tests that do not care about the boundary. */
    static final UUID workspaceId = UUID.randomUUID();

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        registry.register(TenancyActivatedEvent.class);
        var store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        portfolio = PostgresPropertyManagement.portfolioService(store, jdbc);
        service = PostgresPropertyManagement.tenancyService(store, jdbc);
    }

    @Test
    void activationWritesIntegrationEventToOutbox() {
        var unitId = unitIn(workspaceId);
        var tenancyId = service.reserve(workspaceId, command(unitId, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31), "2500", "NAJEM/M1/2026")).tenancyId();

        service.activate(workspaceId, tenancyId, LocalDate.of(2026, 9, 1));

        // scoped to this tenancy: the outbox is shared across test methods in one container
        var outboxTypes = jdbc.queryForList(
            "select event_type from outbox where payload::text like ?", String.class,
            "%" + tenancyId + "%");
        assertThat(outboxTypes).containsExactly("TenancyActivatedEvent");
        assertThat(payloadFor(tenancyId).get("paymentReference").asText()).isEqualTo("NAJEM/M1/2026");
    }

    @Test
    void activationCarriesTheWorkspaceOfTheUnitNotAConstant() {
        var workspaceId = UUID.randomUUID();
        var tenancyId = service.reserve(workspaceId, command(unitIn(workspaceId), LocalDate.of(2026, 10, 1), LocalDate.of(2027, 9, 30), "3000", "NAJEM/M2/2026")).tenancyId();

        service.activate(workspaceId, tenancyId, LocalDate.of(2026, 10, 1));

        assertThat(payloadFor(tenancyId).get("workspaceId").asText()).isEqualTo(workspaceId.toString());
    }

    /**
     * Guards the bridge values the CCR landed with (legalForm="zwykly", depositAmount=null,
     * componentSplitInContract=false, hardcoded). If anyone reintroduces a constant here, the
     * ACL silently picks the wrong statutory deposit cap — so this asserts the payload carries
     * what was actually signed.
     */
    @Test
    void activationPublishesTheRealContractFactsNotDefaults() {
        var unitId = unitIn(workspaceId);
        var tenancyId = service.reserve(workspaceId, new ReserveTenancy(null, null, unitId,
            List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 9, 1),
            new Term.FixedTerm(LocalDate.of(2027, 8, 31)), LegalForm.INSTYTUCJONALNY,
            new MonthlyAmount(new BigDecimal("3000"),
                new MonthlyAmount.Breakdown(new BigDecimal("2600"), new BigDecimal("200"),
                    new BigDecimal("200"))),
            10, new BigDecimal("6000"), "NAJEM/M9/2026")).tenancyId();

        service.activate(workspaceId, tenancyId, LocalDate.of(2026, 9, 1));

        var payload = payloadFor(tenancyId);
        assertThat(payload.get("legalForm").asText()).isEqualTo("instytucjonalny");
        assertThat(payload.get("depositAmount").decimalValue()).isEqualByComparingTo("6000");
        assertThat(payload.get("componentSplitInContract").asBoolean()).isTrue();
        assertThat(payload.get("monthlyTotal").decimalValue()).isEqualByComparingTo("3000");
        assertThat(payload.get("rent").decimalValue()).isEqualByComparingTo("2600");
        assertThat(payload.get("adminFee").decimalValue()).isEqualByComparingTo("200");
        assertThat(payload.get("mediaAdvance").decimalValue()).isEqualByComparingTo("200");
    }

    @Test
    void anUnsplitContractPublishesNoComponentsAndSaysSoExplicitly() {
        var tenancyId = service.reserve(workspaceId, command(unitIn(workspaceId), LocalDate.of(2026, 9, 1),
            LocalDate.of(2027, 8, 31), "2500", "NAJEM/M8/2026")).tenancyId();

        service.activate(workspaceId, tenancyId, LocalDate.of(2026, 9, 1));

        var payload = payloadFor(tenancyId);
        assertThat(payload.get("componentSplitInContract").asBoolean()).isFalse();
        assertThat(payload.get("rent").isNull()).isTrue();
        assertThat(payload.get("adminFee").isNull()).isTrue();
        assertThat(payload.get("mediaAdvance").isNull()).isTrue();
    }

    private static com.fasterxml.jackson.databind.JsonNode payloadFor(UUID tenancyId) {
        String json = jdbc.queryForObject(
            "select payload::text from outbox where payload::text like ? limit 1",
            String.class, "%" + tenancyId + "%");
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable outbox payload: " + json, e);
        }
    }

    private static ReserveTenancy command(UUID unitId, LocalDate start, LocalDate end,
                                          String monthlyTotal, String reference) {
        return new ReserveTenancy(null, null, unitId, List.of(UUID.randomUUID()), List.of(),
            start, new Term.FixedTerm(end), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal(monthlyTotal), null), 10, null, reference);
    }

    private static UUID unitIn(UUID workspaceId) {
        var propertyId = portfolio.createProperty(workspaceId, "Testowa 1",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        return portfolio.addUnit(workspaceId, propertyId, "M1", new BigDecimal("2500"));
    }

    private static UUID activeTenancy() {
        var tenancyId = service.reserve(workspaceId, command(unitIn(workspaceId),
            LocalDate.of(2026, 1, 1), LocalDate.of(2028, 12, 31), "2500",
            "NAJEM/" + UUID.randomUUID())).tenancyId();
        service.activate(workspaceId, tenancyId, LocalDate.of(2026, 1, 1));
        return tenancyId;
    }

    /**
     * The expert-system stance is only real if the flags reach the manager. A statutory warning
     * computed and then swallowed by the service is the same as not computing it.
     */
    @Test
    void schedulingAnUnlawfulUnilateralIncreaseReturnsTheStatutoryWarning() {
        var tenancyId = activeTenancy();

        var warnings = service.scheduleRentChange(workspaceId, tenancyId, LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 6, 1), new MonthlyAmount(new BigDecimal("2600"), null),
            pl.najem.pm.domain.ChangeType.UNILATERAL_INCREASE);

        assertThat(warnings).anyMatch(w -> w.contains("3 months"));
    }

    @Test
    void anagreedChangeAtShortNoticeReturnsNoNoticeWarning() {
        var tenancyId = activeTenancy();

        var warnings = service.scheduleRentChange(workspaceId, tenancyId, LocalDate.of(2026, 5, 25),
            LocalDate.of(2026, 7, 1), new MonthlyAmount(new BigDecimal("2600"), null),
            pl.najem.pm.domain.ChangeType.AGREED_CHANGE);

        assertThat(warnings).noneMatch(w -> w.contains("3 months"));
    }
}
