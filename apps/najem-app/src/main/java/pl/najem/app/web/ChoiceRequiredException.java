package pl.najem.app.web;

/**
 * The acting user belongs to several agencies and has not said which one they are acting in.
 *
 * <p>Not an {@code AccessDeniedException}, for the same reason {@link NoAgencyException} is not:
 * nothing has been refused. The resolver declines to guess — picking one would be a default
 * deciding whose data a request acts on, which roadmap rule 7 forbids — but the answer is a
 * question to the person, not a denial, and "Brak dostępu" would tell someone with legitimate
 * access to two agencies that they had access to neither.
 *
 * <p>Fail-closed is untouched: no workspace is resolved, so no agency's data is served.
 */
public class ChoiceRequiredException extends RuntimeException {

    public ChoiceRequiredException(String message) {
        super(message);
    }
}
