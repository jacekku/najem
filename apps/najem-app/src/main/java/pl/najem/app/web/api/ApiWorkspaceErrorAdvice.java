package pl.najem.app.web.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Answers the two ways a workspace can fail to resolve for an API caller.
 *
 * <p>Distinct exception types rather than reusing the screens' {@code NoAgencyException} and
 * {@code ChoiceRequiredException}, so that this advice and the screens' one can never contend for
 * the same throwable. The screen advice redirects to a chooser and renders pages; doing either to a
 * JSON client would answer a request for data with an HTML login-ish page and a 200 — which is how
 * a caller ends up parsing a redirect as a result.
 */
@RestControllerAdvice
public class ApiWorkspaceErrorAdvice {

    /**
     * 403, not 404. Nothing was named, so nothing can be hidden by the answer: unlike a workspace
     * id the caller guessed at, "you belong to no agency" reveals only something about the caller
     * themselves, which they already know.
     */
    @ExceptionHandler(NoWorkspaceException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> noWorkspace(NoWorkspaceException refused) {
        return Map.of("error", "no-agency", "message", refused.getMessage());
    }

    /**
     * 400: the request is incomplete, not the caller unentitled. Only reachable where the test
     * header resolver is switched on — no packaged deployment can answer this at all.
     */
    @ExceptionHandler(MissingWorkspaceHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> missingHeader(MissingWorkspaceHeaderException missing) {
        return Map.of("error", "no-workspace", "message", missing.getMessage());
    }

    /**
     * 409 rather than 400: the request is well formed and the caller is entitled: what is missing is
     * a decision only they can make. A 400 would invite a client to retry with something added to
     * the request, and there is deliberately nothing they can add.
     */
    @ExceptionHandler(WorkspaceChoiceRequiredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> chooseFirst(WorkspaceChoiceRequiredException choice) {
        return Map.of("error", "choose-agency", "message", choice.getMessage());
    }
}
