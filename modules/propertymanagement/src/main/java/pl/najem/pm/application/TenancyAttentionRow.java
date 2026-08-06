package pl.najem.pm.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One tenancy on an attention list, with enough of its unit and property to be recognised without
 * a second call. {@code on} is whichever date that list watches — start, end, or insurance expiry.
 *
 * <p>Top-level rather than nested in the query that returns it, for the same reason
 * {@code OpenRepair} and {@code OverdueInspection} are: a port and its caller both name it, and a
 * type nested inside one of them makes the other look like it depends on the class rather than the
 * record. Component names are the JSON field names the attention endpoints serve, so renaming one
 * is a wire change and not a rename.
 */
public record TenancyAttentionRow(UUID tenancyId, UUID unitId, String unitName,
                                  String propertyAddress, LocalDate on) {
}
