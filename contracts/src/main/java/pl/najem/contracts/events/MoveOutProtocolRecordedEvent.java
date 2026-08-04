package pl.najem.contracts.events;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Replaces the withdrawn DepositSettlementDueEvent: accounting derives its own settlement
 * deadline from vacateDate + protocolDate so the statutory rule lives in one module.
 * Also the input to media true-up.
 */
public record MoveOutProtocolRecordedEvent(
        UUID workspaceId,
        UUID tenancyId,
        LocalDate protocolDate,
        List<MeterReading> readings) implements IntegrationEvent {
}
