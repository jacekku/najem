package pl.najem.pm.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.pm.domain.InspectionType;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.Property;
import pl.najem.pm.domain.PropertyEvents;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ComplianceService} with no database.
 *
 * <p>{@code ComplianceServiceTest} runs the same rules against Postgres and, per rule 16, wins when
 * the two disagree — the SQL's correlated subquery is the authority on what "latest" selects, and
 * {@link InMemoryInspections} only models it.
 */
class ComplianceRulesTest {

    private InMemoryEventStore store;
    private InMemoryInspections inspections;
    private ComplianceService compliance;
    private PortfolioService portfolio;

    private final UUID agency = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private UUID propertyId;

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore();
        inspections = new InMemoryInspections();
        compliance = new ComplianceService(store, inspections, inspections);
        portfolio = new PortfolioService(store, new InMemoryPortfolioProjection());
        propertyId = portfolio.createProperty(agency, "Testowa 1, Kraków",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        inspections.addressOf(propertyId, "Testowa 1, Kraków");
    }

    @Test
    void anInspectionIsOverdueOnlyOnceItsDeadlineHasPassed() {
        compliance.recordInspection(agency, propertyId, InspectionType.GAS,
            LocalDate.of(2026, 5, 10), "s3://gas.pdf", "no findings");

        assertThat(compliance.overdue(agency, LocalDate.of(2027, 5, 9))).isEmpty();
        assertThat(compliance.overdue(agency, LocalDate.of(2027, 5, 11)))
            .extracting(OverdueInspection::type).containsExactly(InspectionType.GAS);
    }

    @Test
    void areinspectionSupersedesTheOneBeforeItRatherThanAddingASecondObligation() {
        compliance.recordInspection(agency, propertyId, InspectionType.GAS,
            LocalDate.of(2026, 5, 10), null, null);
        compliance.recordInspection(agency, propertyId, InspectionType.GAS,
            LocalDate.of(2027, 4, 1), null, null);

        assertThat(compliance.overdue(agency, LocalDate.of(2027, 6, 1))).isEmpty();
        assertThat(compliance.overdue(agency, LocalDate.of(2028, 4, 2))).hasSize(1);
    }

    @Test
    void differentTypesAreSeparateObligations() {
        compliance.recordInspection(agency, propertyId, InspectionType.GAS,
            LocalDate.of(2027, 1, 1), null, null);
        compliance.recordInspection(agency, propertyId, InspectionType.CHIMNEY,
            LocalDate.of(2026, 1, 1), null, null);

        assertThat(compliance.overdue(agency, LocalDate.of(2027, 6, 1)))
            .extracting(OverdueInspection::type).containsExactly(InspectionType.CHIMNEY);
    }

    @Test
    void anotherAgencySeesNothingOfThisOnesCompliancePosition() {
        compliance.recordInspection(agency, propertyId, InspectionType.GAS,
            LocalDate.of(2026, 5, 10), null, null);

        assertThat(compliance.overdue(stranger, LocalDate.of(2027, 5, 11))).isEmpty();
    }

    @Test
    void astrangerCannotRecordAnInspectionOnAPropertyTheyDoNotOwn() {
        assertThatThrownBy(() -> compliance.recordInspection(stranger, propertyId,
            InspectionType.GAS, LocalDate.of(2026, 5, 10), null, null))
            .isInstanceOf(UnknownInThisWorkspaceException.class);

        assertThat(inspections.rows()).isEmpty();
        assertThat(store.load(propertyId, "Property").events())
            .noneMatch(PropertyEvents.InspectionCompleted.class::isInstance);
    }

    /**
     * The id the API hands back is the id on the event, so a replay of this stream rebuilds the same
     * row. While it was minted beside the insert, every column but the primary key was derived and
     * the table could not honestly be called a projection.
     */
    @Test
    void therecordedIdIsCarriedOnTheEventAndIsTheRowsKey() {
        var inspectionId = compliance.recordInspection(agency, propertyId, InspectionType.SMOKE_CO,
            LocalDate.of(2026, 3, 1), "s3://smoke.pdf", "battery replaced");

        var event = store.load(propertyId, "Property").events().stream()
            .filter(PropertyEvents.InspectionCompleted.class::isInstance)
            .map(PropertyEvents.InspectionCompleted.class::cast)
            .findFirst().orElseThrow();

        assertThat(event.inspectionId()).isEqualTo(inspectionId);
        assertThat(inspections.rows()).singleElement()
            .extracting(InMemoryInspections.Row::inspectionId).isEqualTo(inspectionId);
    }

    /**
     * The deadline on the row is the deadline on the event, not a second application of the
     * statutory interval. If art. 62's cadence is ever amended, the property's record and the
     * manager's screen must not be able to answer differently.
     */
    @Test
    void therowsDeadlineIsTheOneTheEventCarries() {
        compliance.recordInspection(agency, propertyId, InspectionType.ELECTRICAL_5YR,
            LocalDate.of(2026, 5, 10), null, null);

        var event = store.load(propertyId, "Property").events().stream()
            .filter(PropertyEvents.InspectionCompleted.class::isInstance)
            .map(PropertyEvents.InspectionCompleted.class::cast)
            .findFirst().orElseThrow();

        assertThat(inspections.rows()).singleElement()
            .extracting(InMemoryInspections.Row::nextDueOn).isEqualTo(event.nextDueOn());
        assertThat(event.nextDueOn()).isEqualTo(LocalDate.of(2031, 5, 10));
    }

    /**
     * <b>A known divergence, pinned rather than fixed.</b>
     *
     * <p>Back-enter an inspection performed BEFORE one already recorded and the two definitions of
     * "latest" part company: {@code Property.apply} keeps whichever event was applied last, so the
     * aggregate's deadline becomes the backdated one, while the overdue query selects
     * {@code max(performed_on)} and keeps the newer. Both answers are asserted here so that neither
     * can move without this failing.
     *
     * <p>Which is correct under art. 62 is a statutory question, not a refactoring one — the statute
     * cares when an inspection happened, which argues for the query, but a manager correcting a
     * mistyped date is doing something the recording order describes better. Left open on purpose;
     * nothing in production reads the aggregate's answer today, which is precisely why this went
     * unnoticed.
     */
    @Test
    void thebackdatedInspectionShowsTheAggregateAndTheQueryDisagreeing() {
        compliance.recordInspection(agency, propertyId, InspectionType.GAS,
            LocalDate.of(2027, 4, 1), null, null);
        compliance.recordInspection(agency, propertyId, InspectionType.GAS,
            LocalDate.of(2026, 5, 10), null, null);

        var property = Property.from(store.load(propertyId, "Property").events());
        assertThat(property.nextDue(InspectionType.GAS))
            .as("the aggregate keeps the last event applied — the backdated one")
            .contains(LocalDate.of(2027, 5, 10));

        assertThat(compliance.overdue(agency, LocalDate.of(2028, 4, 1)))
            .as("the query keeps max(performed_on) — the 2027 inspection, not yet due")
            .isEmpty();
    }
}
