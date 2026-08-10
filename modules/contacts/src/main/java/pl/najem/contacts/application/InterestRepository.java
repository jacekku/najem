package pl.najem.contacts.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The record of who wants which unit. {@code contacts_interest} holds nothing derived — an interest
 * is registered here and nowhere else — so {@code Repository}, on the same grounds as
 * {@link ContactRepository}.
 */
public interface InterestRepository {

    void insert(UUID interestId, UUID workspaceId, UUID contactId, UUID unitId,
                BigDecimal willingToPay, LocalDate desiredStart);

    /**
     * The interest, if this workspace owns it.
     *
     * <p>This lookup <em>is</em> the workspace gate for every command on an interest: it names the
     * workspace, so a foreign or unknown interest finds nothing and the command is refused before
     * anything is appended. An implementation that answered without filtering on the workspace would
     * open the module's only unguarded write, so the in-memory double filters too (rule 14).
     *
     * <p>It returns the whole {@link Interest} rather than the contact id because status is now part
     * of the answer: {@code withdraw} and {@code convert} both refuse anything that is not active,
     * and two commands each remembering to ask separately is the procedural invariant rule 9 says to
     * replace with a structural one.
     *
     * <p>Empty rather than an exception, deliberately. The Postgres form used {@code queryForObject}
     * once, whose {@code EmptyResultDataAccessException} reaches the edge as a <b>500</b> — so an
     * ordinary "not yours" was reported as the server having broken, and an alert on 5xx fired for
     * routine traffic.
     */
    Optional<Interest> find(UUID workspaceId, UUID interestId);

    void withdraw(UUID workspaceId, UUID interestId);

    /** Closes the interest and records what it became. Scoped to the workspace, like every write here. */
    void convert(UUID workspaceId, UUID interestId, UUID tenancyId);

    List<Interest> activeForUnit(UUID workspaceId, UUID unitId);

    /** Part of erasure: an erased person's interests go with them. */
    void deleteAllFor(UUID workspaceId, UUID contactId);
}
