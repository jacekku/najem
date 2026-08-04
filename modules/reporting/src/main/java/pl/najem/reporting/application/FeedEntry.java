package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * One stored event, as Reporting is allowed to see it: a type name and a tree.
 * <p>
 * Deliberately NOT another module's record class. Reporting has no dependency on any module
 * (najem-build seq 65 pt 2), so a module renaming an internal field breaks a Reporting test —
 * never a compile, and never the module that made the change.
 */
public record FeedEntry(long globalSeq, UUID streamId, String streamType, String eventType, JsonNode payload) {
}
