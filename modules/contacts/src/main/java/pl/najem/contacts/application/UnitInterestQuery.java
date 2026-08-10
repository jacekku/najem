package pl.najem.contacts.application;

import java.util.List;
import java.util.UUID;

/**
 * The active interests in one unit, each already carrying the person's name.
 *
 * <p><b>{@code Query}, not {@code Repository} and not {@code Projection}</b> — architecture.md's
 * third suffix, on the same grounds as {@code SuggestionQuery}. It joins {@code contacts_interest}
 * to {@code contacts_person}, two tables this module already owns; there is no denormalized store
 * to rebuild, so {@code Projection} would claim something about storage that is not true, and
 * {@code Repository} is taken by {@link InterestRepository}, which holds the record.
 *
 * <p><b>Why it exists at all, rather than the caller pairing {@link InterestService#forUnit} with
 * {@link ContactDirectory#find}.</b> That is an N+1 in a controller — the fan-out
 * {@code UnitBoardQuery}'s javadoc warns about — and the alternative of projecting names into
 * Reporting is barred outright: the PII lookaside means Reporting has never seen a name, and a
 * projected copy would survive the {@code contacts_person} deletion that <em>is</em> erasure. See
 * {@link ContactDirectory#search}, which is here for the same reason.
 *
 * <p>Active only. A withdrawn interest is a fact the stream keeps and a screen does not show.
 */
public interface UnitInterestQuery {

    List<InterestedParty> activeForUnit(UUID workspaceId, UUID unitId);
}
