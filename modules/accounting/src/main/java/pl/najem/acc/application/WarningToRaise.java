package pl.najem.acc.application;

import pl.najem.acc.domain.WarningKind;

/**
 * A flag a posting wants raised, before anything has recorded it.
 *
 * <p>Split from {@link Warning} because the two are not the same thing seen twice. This is what a
 * caller knows: a kind and a sentence for a human. A {@code Warning} is what came back out of the
 * register — it has an id to mark seen and names the tenancy it was raised against.
 *
 * <p>They were one record with {@code Warning.of} leaving both of those null and the insert filling
 * them in. That is a shape whose meaning depends on which fields happen to be null, and it survived
 * only while no signature had to say which of the two it wanted.
 */
public record WarningToRaise(WarningKind kind, String detail) {
}
