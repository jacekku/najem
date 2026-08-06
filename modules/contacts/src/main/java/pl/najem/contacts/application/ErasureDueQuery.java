package pl.najem.contacts.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Who has outlived their retention date with nothing holding them.
 *
 * <p>{@code Query} rather than {@code Repository} or {@code Projection} — A7's third suffix. It
 * reads {@code contacts_person} against {@code contacts_retention_hold} and owns neither:
 * {@code Repository} is taken by both tables it names, and {@code Projection} would be a claim that
 * there is a denormalized store to rebuild, which there is not.
 *
 * <p>A port of its own rather than a method on {@link RetentionHoldRepository}, because the query's
 * primary table is the person and the hold register would be reading a record it does not own.
 *
 * <p><b>This and {@link RetentionHoldRepository#activeReasons} are two statements of one rule and
 * must keep agreeing.</b> They once did not: a hold raised by anybody removed the contact from this
 * report while leaving the erasure gate open, so the two disagreed in the direction of
 * <em>keeping</em> personal data past its retention date while telling the operator there was
 * nothing to erase. Any implementation of this interface owes the same workspace scoping the hold
 * register applies.
 */
public interface ErasureDueQuery {

    /** Reports only — erasure stays a deliberate act while hotspot #15 (retention duration) is open. */
    List<UUID> dueForErasure(UUID workspaceId, LocalDate asOf);
}
