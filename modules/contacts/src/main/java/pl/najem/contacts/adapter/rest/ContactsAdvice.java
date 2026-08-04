package pl.najem.contacts.adapter.rest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pl.najem.contacts.application.NoSuchContactException;
import pl.najem.contacts.application.NoSuchInterestException;

/**
 * Scoped to this package's controllers rather than the application, because a module publishing a
 * global advice decides how every other module's errors are rendered.
 * <p>
 * It exists as an advice rather than a handler method because all three contacts controllers can
 * raise {@link NoSuchContactException} — the ownership gate sits in the application layer, so the
 * mapping has to cover every controller that reaches it, not the one that happened to be edited.
 */
@RestControllerAdvice(assignableTypes = {ContactsController.class, InterestsController.class,
    RetentionController.class})
public class ContactsAdvice {

    /**
     * 404, and deliberately the same 404 a genuinely unknown id gets. A distinct status for
     * "exists, but not yours" would let any caller enumerate other agencies' contacts by probing
     * ids — which is exactly the fact the workspace boundary exists to withhold. The body is empty
     * for the same reason.
     */
    @ExceptionHandler(NoSuchContactException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void onUnknownContact(NoSuchContactException e) {
    }

    /**
     * Same treatment for the same reason. Withdrawing an interest that is not yours used to reach
     * the edge as an {@code EmptyResultDataAccessException} and render as a 500 — safe, since the
     * lookup was workspace-scoped, but it reported an outage for an ordinary bad id.
     */
    @ExceptionHandler(NoSuchInterestException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void onUnknownInterest(NoSuchInterestException e) {
    }
}
