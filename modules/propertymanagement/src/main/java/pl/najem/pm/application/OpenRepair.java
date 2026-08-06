package pl.najem.pm.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A repair still outstanding, as a manager's attention list shows it.
 *
 * <p>{@code scope} and {@code statutoryDutyHint} are Strings rather than the domain enums because
 * this record is serialized straight onto the wire by {@code AttentionController}, and the stored
 * spellings are what a client already reads. Turning them into enums here would change the JSON.
 *
 * <p>Was {@code AttentionListsQuery.OpenRepairRow}. It moved out when the read moved behind a port:
 * a type named for the table it came from belongs to whichever class does the reading, and now two
 * classes do.
 */
public record OpenRepair(UUID repairId, String scope, UUID assetId, String description,
                         String statutoryDutyHint, LocalDate reportedOn) {
}
