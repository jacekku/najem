package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenancyChecklistTest {

    private final UUID tenancyId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private ArrayList<Object> reserved() {
        return new ArrayList<>(Tenancy.reserve(new ReserveTenancy(tenancyId, workspaceId,
            UUID.randomUUID(), List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 9, 1),
            new Term.FixedTerm(LocalDate.of(2027, 8, 31)), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, new BigDecimal("2500"), "NAJEM/A")));
    }

    @Test
    void emptyChecklistCountsAsComplete() {
        assertThat(Tenancy.from(reserved()).checklistComplete(ChecklistPhase.PRE_ACTIVATION)).isTrue();
    }

    @Test
    void checklistIsIncompleteUntilEveryItemIsDone() {
        var history = reserved();
        history.addAll(Tenancy.from(history).addChecklistItem("keys", ChecklistPhase.PRE_ACTIVATION));
        history.addAll(Tenancy.from(history).addChecklistItem("photos", ChecklistPhase.PRE_ACTIVATION));
        history.addAll(Tenancy.from(history).completeChecklistItem("keys"));

        assertThat(Tenancy.from(history).checklistComplete(ChecklistPhase.PRE_ACTIVATION)).isFalse();

        history.addAll(Tenancy.from(history).completeChecklistItem("photos"));

        assertThat(Tenancy.from(history).checklistComplete(ChecklistPhase.PRE_ACTIVATION)).isTrue();
    }

    @Test
    void endOfTenancyItemsDoNotBlockActivation() {
        var history = reserved();
        history.addAll(Tenancy.from(history).addChecklistItem("final-meter", ChecklistPhase.END_OF_TENANCY));

        assertThat(Tenancy.from(history).checklistComplete(ChecklistPhase.PRE_ACTIVATION)).isTrue();
        assertThat(Tenancy.from(history).checklistComplete(ChecklistPhase.END_OF_TENANCY)).isFalse();
    }

    @Test
    void completingAnItemThatWasNeverAddedIsRejected() {
        var history = reserved();

        assertThatThrownBy(() -> Tenancy.from(history).completeChecklistItem("keys"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("keys");
    }

    @Test
    void moveInHandoverProtocolIsRecordedWithMeterReadings() {
        var history = reserved();
        var protocol = new HandoverProtocol(ChecklistPhase.PRE_ACTIVATION,
            List.of(new MeterReading("cw-1", "cold-water", new BigDecimal("134.5"))),
            "no damage", List.of("s3://photos/1.jpg"), "s3://docs/protocol.pdf",
            LocalDate.of(2026, 9, 1));

        var events = Tenancy.from(history).recordHandoverProtocol(protocol);

        assertThat(events).containsExactly(
            new TenancyEvents.HandoverProtocolRecorded(workspaceId, tenancyId, protocol));
    }

    @Test
    void moveOutProtocolIsTheOneThatCarriesTheSettlementReadings() {
        var history = reserved();
        var moveOut = new HandoverProtocol(ChecklistPhase.END_OF_TENANCY,
            List.of(new MeterReading("cw-1", "cold-water", new BigDecimal("189.0")),
                new MeterReading("e-1", "electricity", new BigDecimal("4210"))),
            "scuffed wall in hallway", List.of(), "s3://docs/moveout.pdf",
            LocalDate.of(2027, 9, 2));
        history.addAll(Tenancy.from(history).recordHandoverProtocol(moveOut));

        var recorded = Tenancy.from(history).handoverProtocol(ChecklistPhase.END_OF_TENANCY).orElseThrow();

        assertThat(recorded.meterReadings()).hasSize(2);
        assertThat(recorded.date()).isEqualTo(LocalDate.of(2027, 9, 2));
    }
}
