package pl.najem.app.web;

/**
 * The acting user belongs to no agency yet.
 *
 * <p>Deliberately NOT an {@code AccessDeniedException}. Nothing is being refused: there is no
 * agency to refuse access to, and the person is very often someone who has just been invited and
 * has not yet been added. Refusing access and refusing to explain are separate decisions, and until
 * now this path made both — it threw the same exception as a genuine denial, so a new user met a
 * page telling them they had no access to an agency they had never asked for.
 *
 * <p>Fail-closed still holds, because it is about permissions rather than copy: the screen this
 * renders carries no workspace and therefore shows no agency's data.
 */
public class NoAgencyException extends RuntimeException {

    public NoAgencyException(String message) {
        super(message);
    }
}
