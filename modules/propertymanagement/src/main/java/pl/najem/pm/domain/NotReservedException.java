package pl.najem.pm.domain;

/**
 * A cancel posted against a tenancy that is no longer RESERVED — activated, ended, or already
 * cancelled. The screen does not offer the button once that is true, but a stale tab can still post
 * it, so this is a stale-tab refusal (409) and not a bug: {@link Tenancy#cancelReservation} decides
 * this from the same aggregate {@link pl.najem.pm.application.TenancyService#isReserved} answered
 * from moments earlier, so the two can only disagree on timing, never on the rule.
 *
 * <p>Its own type rather than a bare {@link IllegalStateException} so that a handler can map this
 * one refusal without also turning every other invariant violation in this aggregate into a tidy
 * status code.
 */
public class NotReservedException extends IllegalStateException {

    public NotReservedException(String message) {
        super(message);
    }
}
