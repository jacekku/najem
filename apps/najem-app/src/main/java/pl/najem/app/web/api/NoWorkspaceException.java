package pl.najem.app.web.api;

/**
 * The caller is somebody, but belongs to no agency — so there is no workspace for their request to
 * act in.
 *
 * <p>Its own type rather than {@code AccessDeniedException}: nothing has been refused. NAJEM is
 * invite-only, and a person who has signed in before anyone added them to an agency is in an
 * ordinary state of the product. The screens render a page saying so; an API caller has no page,
 * so this becomes a status code.
 */
public class NoWorkspaceException extends RuntimeException {

    public NoWorkspaceException(String message) {
        super(message);
    }
}
