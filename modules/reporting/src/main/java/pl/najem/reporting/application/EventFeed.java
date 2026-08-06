package pl.najem.reporting.application;

import java.util.List;
import java.util.Set;

/**
 * Reporting's window onto the shared event store, ordered by {@code global_seq}.
 * <p>
 * The coordinator sanctioned Reporting as a privileged read-side <em>restricted to an allowlist
 * of streams</em> (najem-build seq 65 pt 2, amending an earlier proposal to read the table
 * wholesale). Streams are default-private: everything not named below belongs to its owning
 * module and stays there.
 * <p>
 * The restriction lives here, in the one place events enter Reporting, rather than in each
 * projection — a rule every caller must remember is a rule that will eventually be forgotten,
 * and the thing being protected is other modules' private data.
 * <p>
 * <b>Why the allowlist is on the port and the SQL is in the adapter.</b> Which streams Reporting
 * may read is policy, and rule 10 puts policy in the layer that owns the decision: it must not
 * vary with the store, and a second implementation must be bound by it rather than free to choose.
 * How that policy is enforced against a table is mechanism, and it belongs to the adapter. The
 * split is also what makes {@link #ALLOWED_STREAMS} testable without a database, which is what
 * lets the fast tier assert a forbidden stream never reaches a projection.
 * <p>
 * <b>An implementation MUST NOT read the store through {@code EventStore.load}.</b> That is not a
 * style choice: {@code load} keys on {@code stream_id} alone and ignores {@code stream_type}
 * (najem-build seq 103), so routing the feed through it would hand back every type sharing a
 * stream id and silently widen the allowlist past what was granted. The one place
 * {@code stream_type} is load-bearing for a security boundary is the one place that does not use
 * {@code load} — keep it that way even once that defect is fixed, because this guarantee should
 * not depend on someone else's signature staying filtered.
 */
public interface EventFeed {

    /**
     * Per-owner, as ruled:
     * PM {@code Property/Unit/Tenancy} · Accounting {@code TenancyLedger/Payment} ·
     * Contacts all (PII-free by construction) · UserManagement {@code Workspace/User} only.
     * <p>
     * Adding a stream here is a cross-module decision, not a local one: it makes another
     * module's payloads a published interface. Post on najem-build first.
     */
    Set<String> ALLOWED_STREAMS =
        Set.of("Property", "Unit", "Tenancy", "TenancyLedger", "Payment", "Contact", "Workspace", "User");

    /**
     * The next batch of allowlisted events after {@code afterGlobalSeq}, in order.
     * <p>
     * Filtering must happen before the limit is applied, so that a run of forbidden events cannot
     * eat the batch window and stall the cursor — the caller advances past them without ever
     * seeing them. Gaps in {@code global_seq} (rolled-back transactions) are normal and must never
     * be waited for.
     */
    List<FeedEntry> since(long afterGlobalSeq, int limit);
}
