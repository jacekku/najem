package pl.najem.contacts.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The register of reasons a contact may not yet be erased. A record, not a projection: a hold is
 * raised here and exists nowhere else.
 *
 * <p>Keyed by (contact, reason, source) rather than (contact, reason). V40 keyed it the short way
 * and a contact who was tenant or guarantor on two tenancies then had one row for
 * "ledger-referenced": the first tenancy to close released it, and the person became erasable while
 * the second tenancy's ledger was still live. The double models the three-part key for that reason —
 * a fake keyed the old way could not fail the test that catches it.
 */
public interface RetentionHoldRepository {

    /**
     * Raises {@code reason} on behalf of {@code sourceRef}, or re-asserts it.
     *
     * <p>Deliberately idempotent, and re-asserting also clears a previous release: a trigger
     * re-fanning-out over a changed roster will send the same hold repeatedly, and that is normal
     * traffic rather than an error.
     */
    void set(UUID workspaceId, UUID contactId, String reason, String sourceRef, LocalDate setOn);

    /** Releases only {@code sourceRef}'s hold. Another source's hold on the same reason survives. */
    void release(UUID workspaceId, UUID contactId, String reason, String sourceRef,
                 LocalDate releasedOn);

    /** The reasons erasure is currently blocked for — distinct, because a caller needs causes, not rows. */
    List<String> activeReasons(UUID workspaceId, UUID contactId);
}
