package pl.najem.app.web.api;

/**
 * The test header resolver is active and the request did not carry a usable header.
 *
 * <p>Distinct from {@link NoWorkspaceException}, which means the caller genuinely belongs to no
 * agency. Here the request simply left out a field it was required to send, so it is a 400 — the
 * same answer Spring gave when the header was a {@code required=true} parameter, which is why the
 * end-to-end suite's refusal assertions did not have to change shape when the header moved behind a
 * resolver.
 *
 * <p>Collapsing the two would have been easy and wrong: a test asserting "a request with no
 * workspace is refused" would then pass identically whether the application had rejected an
 * incomplete request or decided the caller had no agencies at all, and only one of those is about
 * the request.
 */
public class MissingWorkspaceHeaderException extends RuntimeException {

    public MissingWorkspaceHeaderException(String message) {
        super(message);
    }
}
