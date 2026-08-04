package pl.najem.pm.adapter.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pl.najem.pm.domain.OverlappingTenancyException;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;

import java.util.Map;

/**
 * Domain failures as statuses a caller can act on, scoped to PM's controllers so it cannot
 * change how another module reports its own errors.
 *
 * <p>The distinction that matters: a 400 says the request was wrong, a 409 says the request was
 * fine but conflicts with what is already true. Reserving an overlapping tenancy is the second —
 * the caller did nothing malformed, the unit is simply taken.
 */
@RestControllerAdvice(basePackageClasses = PmExceptionHandler.class)
public class PmExceptionHandler {

    @ExceptionHandler(OverlappingTenancyException.class)
    public ResponseEntity<Map<String, String>> handle(OverlappingTenancyException ex) {
        return body(HttpStatus.CONFLICT, ex);
    }

    /** 404, not 403: a caller must not learn that another agency's id exists. */
    @ExceptionHandler(UnknownInThisWorkspaceException.class)
    public ResponseEntity<Map<String, String>> handle(UnknownInThisWorkspaceException ex) {
        return body(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handle(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handle(IllegalStateException ex) {
        return body(HttpStatus.CONFLICT, ex);
    }

    /**
     * Never renders a null message as "null". A caller reading an error body is already having a
     * bad time; the class name at least tells them what kind.
     */
    private static ResponseEntity<Map<String, String>> body(HttpStatus status, Exception ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage();
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
