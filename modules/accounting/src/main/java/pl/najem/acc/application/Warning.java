package pl.najem.acc.application;

import pl.najem.acc.domain.WarningKind;

import java.util.UUID;

/**
 * A raised compliance flag, as it comes back out of the register. Never blocks the posting — this
 * is an expert system, not a gate.
 *
 * <p>Every field is populated: the id is what marks it seen, and the tenancy is what the manager
 * needs to act on it. What a caller hands in before any of that exists is a {@link WarningToRaise}.
 */
public record Warning(UUID warningId, UUID tenancyId, WarningKind kind, String detail) {
}
