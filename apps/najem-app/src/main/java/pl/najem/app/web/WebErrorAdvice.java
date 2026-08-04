package pl.najem.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Turns a refused workspace into a 403 the screens can render, rather than leaving it to whatever
 * the security chain would do with it.
 *
 * <p>Scoped to this package so it cannot change how any module's API behaves: the modules answer to
 * their own callers and this advice must not quietly restyle their errors.
 */
@ControllerAdvice(basePackageClasses = WebErrorAdvice.class)
public class WebErrorAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String refused() {
        return "error/forbidden";
    }
}
