package pl.najem.pm.application;

import pl.najem.pm.domain.Unit;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The read side of the portfolio: what {@code pm_property} and {@code pm_unit} are told after an
 * aggregate has decided something.
 *
 * <p><b>Projection, not Repository, and the suffix is load-bearing.</b> These tables hold nothing
 * that is not already in the Property and Unit event streams — drop them and a replay rebuilds
 * them. The record is the stream. Naming this a repository would invite the one thing that must not
 * happen: a service reading a row here to decide something, at which point the copy stops being
 * derived and becomes an opinion that a lagging write can make wrong. Ownership checks go to
 * {@code Unit.requireOwnedBy} and {@code Property.requireOwnedBy}, which ask the record.
 *
 * <p>So this port is deliberately write-only. There is no {@code find} on it, and adding one is the
 * decision to think hard about rather than the obvious next method.
 *
 * <p>Named for what happened rather than for the column that moves — {@code marketStateSet} rather
 * than {@code updateMarketState}. The projection's job is to be told; phrasing the port as
 * instructions to a table is how the SQL's shape starts leaking back into the caller.
 *
 * <p>Every method takes the workspace, and the implementation must scope its write by it. That is
 * not the same check as the aggregate's: the aggregate refuses the wrong caller, this stops a
 * mis-keyed statement touching the wrong agency's row, and neither makes the other redundant.
 */
public interface PortfolioProjection {

    void propertyCreated(UUID propertyId, UUID workspaceId, String address);

    void unitAdded(UUID unitId, UUID propertyId, UUID workspaceId, String name,
                   BigDecimal baseRent, Unit.MarketState marketState);

    void baseRentSet(UUID unitId, UUID workspaceId, BigDecimal amount);

    void marketStateSet(UUID unitId, UUID workspaceId, Unit.MarketState marketState);

    void listingRefSet(UUID unitId, UUID workspaceId, String listingRef);
}
