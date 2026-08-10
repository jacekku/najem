package pl.najem.pm.domain;

/**
 * The standing a contact has on a tenancy.
 *
 * <p>Two lists on {@link ReserveTenancy} that were only ever distinguished by which parameter they
 * arrived in. Naming the distinction is what lets one projected table hold both without the role
 * becoming a string somebody spells differently in the second place it is written.
 *
 * <p>The names are stored in {@code pm_tenancy_party.role}, so renaming a constant here is a
 * migration and not a rename — {@code TenancyPartyRoleTest} pins the wire values for exactly that
 * reason (refactoring rule 12).
 */
public enum PartyRole {

    /** Signs the agreement and owes the rent. A tenancy with none of these cannot be reserved. */
    TENANT,

    /** Stands behind the tenant's obligations. Optional, and never the person who owes. */
    GUARANTOR
}
