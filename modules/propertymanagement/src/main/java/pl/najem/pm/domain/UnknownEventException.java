package pl.najem.pm.domain;

/**
 * An aggregate was handed an event its rebuild loop does not know.
 *
 * <p>Deliberately NOT an {@link IllegalArgumentException}, which is what these branches used to
 * throw. Every screen in this application converts a bare {@code IllegalArgumentException} out of a
 * PM command into a 400, because that is what the creation guards raise — so an event type somebody
 * forgot to register reached the manager as "your request was bad", with a Java class name in the
 * body. It is not the manager's request that is wrong; it is this application, and the honest answer
 * is the one nothing maps: a 500.
 *
 * <p>Structural rather than procedural (refactoring rule 9): the alternative was for each caller to
 * remember to narrow its {@code catch}, which holds only for the callers that remember.
 */
public class UnknownEventException extends RuntimeException {

    public UnknownEventException(Object event) {
        super("Unknown event: " + (event == null ? "null" : event.getClass()));
    }
}
