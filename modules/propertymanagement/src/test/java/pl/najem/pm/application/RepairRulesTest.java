package pl.najem.pm.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.RepairScope;
import pl.najem.pm.domain.StatutoryDutyHint;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RepairService} with no database. {@code RepairServiceTest} runs the same rules against
 * Postgres and wins when the two disagree.
 */
class RepairRulesTest {

    private InMemoryEventStore store;
    private InMemoryRepairs repairs;
    private RepairService service;
    private PortfolioService portfolio;

    private final UUID agency = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private UUID propertyId;
    private UUID unitId;

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore();
        repairs = new InMemoryRepairs();
        portfolio = new PortfolioService(store, new InMemoryPortfolioProjection());
        service = new RepairService(store, repairs, portfolio);
        propertyId = portfolio.createProperty(agency, "Testowa 1, Kraków",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        unitId = portfolio.addUnit(agency, propertyId, "M1", new BigDecimal("2500"));
    }

    private UUID reportOnUnit() {
        return service.report(agency, RepairScope.UNIT, unitId, "leaking tap", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 8, 1));
    }

    @Test
    void arepairInheritsTheWorkspaceOfTheAssetItIsReportedAgainst() {
        var repairId = reportOnUnit();

        assertThat(repairs.repair(repairId).orElseThrow().workspaceId()).isEqualTo(agency);
    }

    @Test
    void arepairCanHangOffAPropertyAsWellAsAUnit() {
        var repairId = service.report(agency, RepairScope.PROPERTY, propertyId, "roof", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 8, 1));

        assertThat(repairs.repair(repairId).orElseThrow().scope()).isEqualTo(RepairScope.PROPERTY);
    }

    @Test
    void completingARepairTakesItOffTheOpenList() {
        var repairId = reportOnUnit();
        assertThat(repairs.openRepairs(agency)).extracting(OpenRepair::repairId)
            .containsExactly(repairId);

        service.complete(agency, repairId, LocalDate.of(2026, 8, 3), "done");

        assertThat(repairs.openRepairs(agency)).isEmpty();
    }

    /** Oldest first, so the thing most likely to have been forgotten is at the top. */
    @Test
    void theopenListIsOrderedByWhenTheRepairWasReported() {
        var later = service.report(agency, RepairScope.UNIT, unitId, "later", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 1));
        var earlier = service.report(agency, RepairScope.UNIT, unitId, "earlier", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 7, 1));

        assertThat(repairs.openRepairs(agency)).extracting(OpenRepair::repairId)
            .containsExactly(earlier, later);
    }

    @Test
    void astrangerCannotHangARepairOffSomeoneElsesAsset() {
        assertThatThrownBy(() -> service.report(stranger, RepairScope.UNIT, unitId, "mine now",
            null, StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 8, 1)))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.report(stranger, RepairScope.PROPERTY, propertyId, "roof",
            null, StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 8, 1)))
            .isInstanceOf(UnknownInThisWorkspaceException.class);

        assertThat(repairs.openRepairs(stranger)).isEmpty();
    }

    /**
     * The gap that this class of defect was named for: POST /repairs/&#123;id&#125;/complete could
     * finish a repair in any agency. The guard closed it at the controller; the service closes it
     * for every caller.
     */
    @Test
    void astrangerCannotCompleteSomeoneElsesRepair() {
        var repairId = reportOnUnit();

        assertThatThrownBy(() -> service.complete(stranger, repairId, LocalDate.of(2026, 8, 3), "x"))
            .isInstanceOf(UnknownInThisWorkspaceException.class);

        assertThat(repairs.repair(repairId).orElseThrow().completedOn()).isNull();
        assertThat(repairs.openRepairs(agency)).hasSize(1);
    }

    /**
     * Completing twice is refused by the aggregate, which is where it belongs — the projection
     * would happily overwrite the date, and the second caller would be told it worked.
     */
    @Test
    void arepairCannotBeCompletedTwice() {
        var repairId = reportOnUnit();
        service.complete(agency, repairId, LocalDate.of(2026, 8, 3), "done");

        assertThatThrownBy(() -> service.complete(agency, repairId, LocalDate.of(2026, 9, 9), "again"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already completed");

        assertThat(repairs.repair(repairId).orElseThrow().completedOn())
            .isEqualTo(LocalDate.of(2026, 8, 3));
    }

    @Test
    void oneAgencysOpenRepairsAreNotAnothers() {
        reportOnUnit();
        var theirProperty = portfolio.createProperty(stranger, "Cudza 9",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        service.report(stranger, RepairScope.PROPERTY, theirProperty, "their roof", null,
            StatutoryDutyHint.TENANT, LocalDate.of(2026, 8, 1));

        assertThat(repairs.openRepairs(agency)).hasSize(1);
        assertThat(repairs.openRepairs(stranger)).hasSize(1);
    }

    /** Rule 7: neither the scope nor the duty hint may be defaulted. */
    @Test
    void ascopelessOrHintlessRepairIsRejected() {
        assertThatThrownBy(() -> service.report(agency, null, unitId, "x", null,
            StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 8, 1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.report(agency, RepairScope.UNIT, unitId, "x", null,
            null, LocalDate.of(2026, 8, 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
