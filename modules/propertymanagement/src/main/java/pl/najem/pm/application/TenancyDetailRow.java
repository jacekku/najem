package pl.najem.pm.application;

import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.Tenancy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One tenancy in full — everything a screen showing a single tenancy needs, in one row.
 *
 * <p><b>Not a wider {@link TenancyBoardRow}.</b> The register renders every tenancy an agency has
 * and needs six facts about each; this answers "show me this contract" and needs sixteen. Widening
 * the register's row to carry them would make every list read pull a deposit and a payment
 * reference it never renders, and would put the two questions on one type where a change for one
 * screen lands on the other. Different question, different record — the same split A7 draws
 * between a record's port and a view's.
 *
 * @param legalForm        what kind of tenancy was signed. Fixed at signing and never re-derived.
 * @param monthlyTotal     what the tenant owes each month, always present.
 * @param rent             the three components, and every one of them nullable — {@code rent},
 *                         {@code adminFee} and {@code mediaAdvance} are set only when the CONTRACT
 *                         declares a split, which {@code componentSplit} states outright. "No
 *                         split" and "a split with a zero admin fee" are legally different (V22),
 *                         so a caller must read the flag rather than infer it from a null.
 * @param depositAmount    nullable: a tenancy may be signed without one.
 * @param tenantContactIds every tenant, contact-id order, as the register's own list is ordered.
 * @param guarantorContactIds everyone standing behind the tenant. Usually empty.
 */
public record TenancyDetailRow(UUID tenancyId, UUID unitId, String unitName, String propertyAddress,
                               Tenancy.State state, LocalDate startDate, LocalDate endDate,
                               LegalForm legalForm, BigDecimal monthlyTotal, BigDecimal rent,
                               BigDecimal adminFee, BigDecimal mediaAdvance, boolean componentSplit,
                               int rentDay, BigDecimal depositAmount, String paymentReference,
                               List<UUID> tenantContactIds, List<UUID> guarantorContactIds) {
}
