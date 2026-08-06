package pl.najem.app.web.api;

/**
 * The caller belongs to several agencies and nothing has said which one this request acts in.
 *
 * <p>A screen resolves this by asking the person; an API caller cannot be asked mid-request, so the
 * request is refused. <b>Not a permissions failure</b> — the caller legitimately has access to every
 * one of them, which is exactly why the application may not choose. Picking the first would be a
 * default deciding whose books a write lands in, and a write that lands in the wrong agency does not
 * announce itself.
 *
 * <p>A browser session that has chosen an agency never reaches here: the choice is honoured for
 * {@code /api/**} calls made from a screen. Only a bearer-token client with several memberships
 * does, and the answer for it is a token scoped to one agency rather than a field it gets to assert.
 */
public class WorkspaceChoiceRequiredException extends RuntimeException {

    public WorkspaceChoiceRequiredException(String message) {
        super(message);
    }
}
