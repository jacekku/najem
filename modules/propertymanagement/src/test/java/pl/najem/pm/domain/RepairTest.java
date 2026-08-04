package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepairTest {

    private final UUID repairId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID unitId = UUID.randomUUID();
    private final UUID tenancyId = UUID.randomUUID();

    /**
     * A repair belongs to the thing that is broken, not to whoever happened to be living there.
     * The tenancy is a cause, not an owner — a leaking tap outlives the tenant who reported it,
     * and its history has to survive them.
     */
    @Test
    void repairAttachesToThePhysicalAssetNotTheTenancy() {
        var events = Repair.report(repairId, workspaceId, RepairScope.UNIT, unitId,
            "leaking tap", tenancyId, StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5));

        var repair = Repair.from(events);
        assertThat(repair.assetId()).isEqualTo(unitId);
        assertThat(repair.causedByTenancy()).isEqualTo(tenancyId);
        assertThat(repair.isOpen()).isTrue();
    }

    @Test
    void arepairNeedNotBeCausedByAnyTenancy() {
        var repair = Repair.from(Repair.report(repairId, workspaceId, RepairScope.PROPERTY,
            UUID.randomUUID(), "roof leak", null, StatutoryDutyHint.LANDLORD,
            LocalDate.of(2026, 9, 5)));

        assertThat(repair.causedByTenancy()).isNull();
        assertThat(repair.scope()).isEqualTo(RepairScope.PROPERTY);
    }

    @Test
    void completingClosesTheRepair() {
        var history = open();
        history.addAll(Repair.from(history).complete(LocalDate.of(2026, 9, 10), "plumber done"));

        var repair = Repair.from(history);
        assertThat(repair.isOpen()).isFalse();
        assertThat(repair.completedOn()).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    void completingTwiceIsRejected() {
        var history = open();
        history.addAll(Repair.from(history).complete(LocalDate.of(2026, 9, 10), "plumber done"));

        assertThatThrownBy(() -> Repair.from(history).complete(LocalDate.of(2026, 9, 11), "again"))
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The hint is advice, not adjudication. Who pays for what under art. 6a-6b is contested often
     * enough that the software must not decide it — it records what the manager concluded, and
     * NEGOTIABLE is a real answer rather than a missing one.
     */
    @Test
    void thestatutoryDutyHintIsRecordedAndNeverDecided() {
        var repair = Repair.from(Repair.report(repairId, workspaceId, RepairScope.UNIT, unitId,
            "cracked basin, tenant says it was already chipped", tenancyId,
            StatutoryDutyHint.NEGOTIABLE, LocalDate.of(2026, 9, 5)));

        assertThat(repair.statutoryDutyHint()).isEqualTo(StatutoryDutyHint.NEGOTIABLE);
    }

    /** Rule 7: the hint decides who a manager will bill, so it is never defaulted. */
    @Test
    void amissingStatutoryDutyHintIsRejected() {
        assertThatThrownBy(() -> Repair.report(repairId, workspaceId, RepairScope.UNIT, unitId,
                "leaking tap", null, null, LocalDate.of(2026, 9, 5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duty");
    }

    @Test
    void amissingScopeOrAssetIsRejected() {
        assertThatThrownBy(() -> Repair.report(repairId, workspaceId, null, unitId,
                "leaking tap", null, StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5)))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Repair.report(repairId, workspaceId, RepairScope.UNIT, null,
                "leaking tap", null, StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ArrayList<Object> open() {
        return new ArrayList<>(Repair.report(repairId, workspaceId, RepairScope.UNIT, unitId,
            "leaking tap", null, StatutoryDutyHint.LANDLORD, LocalDate.of(2026, 9, 5)));
    }
}
