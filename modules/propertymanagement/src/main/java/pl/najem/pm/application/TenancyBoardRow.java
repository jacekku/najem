package pl.najem.pm.application;

import pl.najem.pm.domain.Tenancy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One tenancy on the register: who is on it, which unit it occupies, when it runs, what it costs.
 *
 * <p>Top-level rather than nested in the port that returns it, following {@link TenancyAttentionRow}
 * and for the reason its javadoc gives — a port and its caller both name this type, and nesting it
 * inside one of them makes the other look like it depends on the class rather than the record.
 *
 * @param tenantContactIds ids, never names. PM holds no PII and this record does not become the
 *                         first place it does; resolving a contact to a person is the contacts
 *                         module's job and joining the two is the composition root's.
 * @param endDate          null is an indefinite tenancy, not a missing value — the unit does not
 *                         free up at all. A renderer that prints an em dash for it is wrong.
 */
public record TenancyBoardRow(UUID tenancyId, UUID unitId, String unitName, String propertyAddress,
                              Tenancy.State state, LocalDate startDate, LocalDate endDate,
                              BigDecimal monthlyTotal, List<UUID> tenantContactIds) {
}
