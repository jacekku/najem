package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code sourceRef} names what justifies the hold — a tenancyId, or {@code "manual"} for a
 * hold a manager set by hand. Two sources raising the same reason are two holds, and each
 * releases on its own; see V41 for why that distinction is load-bearing.
 */
public record RetentionHoldSet(UUID workspaceId, UUID contactId, String reason, String sourceRef, LocalDate setOn) {
}
