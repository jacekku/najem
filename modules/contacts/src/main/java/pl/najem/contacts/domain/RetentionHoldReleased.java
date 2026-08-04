package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Releases ONE source's hold. Other sources' holds on the same reason stand. */
public record RetentionHoldReleased(UUID workspaceId, UUID contactId, String reason, String sourceRef,
                                    LocalDate releasedOn) {
}
