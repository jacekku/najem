package pl.najem.acc.application;

import pl.najem.acc.domain.ArrearsStanding;

import java.util.UUID;

/**
 * Where the arrears board is kept so that it can be looked at.
 *
 * <p>A projection rather than a repository, and the name is the guardrail: everything here can be
 * dropped and rebuilt from the charges, so nothing may read it to make a decision. The moment a
 * service consults a stored colour, the board stops being derived and becomes an opinion — which is
 * the failure the board was built to get out of, and it came back looking like a colour bug rather
 * than a missing call.
 */
public interface ArrearsStandingProjection {

    /** Records what the board now says about one tenancy, replacing whatever it said before. */
    void save(UUID workspaceId, UUID tenancyId, ArrearsStanding standing);
}
