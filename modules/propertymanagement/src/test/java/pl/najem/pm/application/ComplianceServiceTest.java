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
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.application.ComplianceService.OverdueInspection;
import pl.najem.pm.domain.InspectionType;
import pl.najem.pm.domain.Owner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class ComplianceServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static PortfolioService portfolio;
    static ComplianceService compliance;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        portfolio = new PortfolioService(store, jdbc);
        compliance = new ComplianceService(store, jdbc);
    }

    @Test
    void overdueInspectionsAreReportedPerWorkspace() {
        var workspaceId = UUID.randomUUID();
        var propertyId = portfolio.createProperty(workspaceId, "Testowa 1", owners());
        compliance.recordInspection(propertyId, InspectionType.GAS, LocalDate.of(2026, 5, 10),
            "s3://docs/gas.pdf", "ok");

        assertThat(compliance.overdue(workspaceId, LocalDate.of(2027, 5, 9))).isEmpty();
        assertThat(compliance.overdue(workspaceId, LocalDate.of(2027, 5, 11)))
            .extracting(OverdueInspection::type).containsExactly(InspectionType.GAS);
        // The workspace-isolation assertion: another agency sees nothing of this one's.
        assertThat(compliance.overdue(UUID.randomUUID(), LocalDate.of(2027, 5, 11))).isEmpty();
    }

    /**
     * The false-alarm case. An annual check done two years running is ONE obligation; reporting
     * the superseded row as overdue would put a compliant property on the manager's attention
     * list every year forever, which is how an attention list stops being read.
     */
    @Test
    void areinspectionSupersedesTheEarlierOneRatherThanAddingASecondOverdueRow() {
        var workspaceId = UUID.randomUUID();
        var propertyId = portfolio.createProperty(workspaceId, "Odnowiona 2", owners());
        compliance.recordInspection(propertyId, InspectionType.GAS, LocalDate.of(2026, 5, 10),
            null, "ok");
        compliance.recordInspection(propertyId, InspectionType.GAS, LocalDate.of(2027, 4, 1),
            null, "ok");

        assertThat(compliance.overdue(workspaceId, LocalDate.of(2027, 6, 1))).isEmpty();
        assertThat(compliance.overdue(workspaceId, LocalDate.of(2028, 4, 2)))
            .extracting(OverdueInspection::nextDueOn).containsExactly(LocalDate.of(2028, 4, 1));
    }

    /** Different types are independent obligations — a current gas check does not cover chimney. */
    @Test
    void onetypeBeingCurrentDoesNotCoverAnother() {
        var workspaceId = UUID.randomUUID();
        var propertyId = portfolio.createProperty(workspaceId, "Kominowa 3", owners());
        compliance.recordInspection(propertyId, InspectionType.GAS, LocalDate.of(2027, 1, 1),
            null, "ok");
        compliance.recordInspection(propertyId, InspectionType.CHIMNEY, LocalDate.of(2026, 1, 1),
            null, "ok");

        assertThat(compliance.overdue(workspaceId, LocalDate.of(2027, 6, 1)))
            .extracting(OverdueInspection::type).containsExactly(InspectionType.CHIMNEY);
    }

    /** The five-year electrical check is not annual, and getting that wrong nags every year. */
    @Test
    void thefiveYearElectricalCheckIsNotOverdueAfterOneYear() {
        var workspaceId = UUID.randomUUID();
        var propertyId = portfolio.createProperty(workspaceId, "Prądowa 4", owners());
        compliance.recordInspection(propertyId, InspectionType.ELECTRICAL_5YR,
            LocalDate.of(2026, 5, 10), null, "ok");

        assertThat(compliance.overdue(workspaceId, LocalDate.of(2027, 5, 11))).isEmpty();
        assertThat(compliance.overdue(workspaceId, LocalDate.of(2031, 5, 11)))
            .extracting(OverdueInspection::type).containsExactly(InspectionType.ELECTRICAL_5YR);
    }

    @Test
    void theoverdueRowCarriesEnoughToNameThePropertyOnTheAttentionList() {
        var workspaceId = UUID.randomUUID();
        var propertyId = portfolio.createProperty(workspaceId, "Adresowa 5, Kraków", owners());
        compliance.recordInspection(propertyId, InspectionType.SMOKE_CO, LocalDate.of(2026, 3, 1),
            null, "ok");

        var overdue = compliance.overdue(workspaceId, LocalDate.of(2027, 3, 2)).getFirst();

        assertThat(overdue.propertyId()).isEqualTo(propertyId);
        assertThat(overdue.address()).isEqualTo("Adresowa 5, Kraków");
        assertThat(overdue.performedOn()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(overdue.nextDueOn()).isEqualTo(LocalDate.of(2027, 3, 1));
    }

    private static List<Owner> owners() {
        return List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")));
    }
}
