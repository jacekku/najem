package pl.najem.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import pl.najem.um.application.NotInvitedException;
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

    /**
     * Still a refusal and still a 403 — being signed in grants nothing here. What changes is the
     * words: NAJEM is invite-only, so somebody with a valid Keycloak account and no invitation has
     * an invitation problem, and "Brak dostępu" describes a permissions problem they do not have.
     * Ordered before the AccessDeniedException handler by being the more specific type.
     */
    @ExceptionHandler(NotInvitedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String notInvited() {
        return "error/not-invited";
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String refused() {
        return "error/forbidden";
    }

    /**
     * Not a 403. A person who has been invited but not yet added belongs to no agency, and that is
     * an ordinary state of the product rather than a refusal — so it renders a screen that explains
     * it. The response carries no agency's data, so nothing is opened by answering 200.
     */
    @ExceptionHandler(NoAgencyException.class)
    @ResponseStatus(HttpStatus.OK)
    public String noAgencyYet() {
        return "error/no-agency";
    }

    /**
     * Also not a 403. Belonging to two agencies and having named neither is a question, not a
     * refusal — telling someone with legitimate access to both that they have access to neither
     * would be false. Nothing is resolved and nothing is served until they answer.
     */
    @ExceptionHandler(ChoiceRequiredException.class)
    public String chooseFirst() {
        return "redirect:/agencies";
    }
}
