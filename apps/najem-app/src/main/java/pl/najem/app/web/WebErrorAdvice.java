package pl.najem.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import pl.najem.contacts.application.InterestNotActiveException;
import pl.najem.contacts.application.NoSuchContactException;
import pl.najem.contacts.application.NoSuchInterestException;
import pl.najem.pm.domain.NotReservedException;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;
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
// TODO: every handler here that answers a bare status renders as a blank browser error page —
// Chrome's "this page isn't working", with none of NAJEM's chrome and no way back. templates/error
// holds only forbidden, no-agency and not-invited, so there is no generic 400/404/500 page for the
// rest to fall through to. This predates the add-property screens but they made it easy to reach:
// clicking a search hit for somebody already drafted is a 400, and it lands the manager on a blank
// page mid-form. Two separable pieces of work — a generic error template, and (see
// AddPropertyScreenController) not answering 400 for something the manager can see on screen.
@ControllerAdvice(basePackageClasses = WebErrorAdvice.class)
public class WebErrorAdvice {

    /**
     * Still a refusal and still a 403 — being signed in grants nothing here. What changes is the
     * words: NAJEM is invite-only, so somebody with a valid Keycloak account and no invitation has
     * an invitation problem, and "Brak dostępu" describes a permissions problem they do not have.
     * Ordered before the AccessDeniedException handler by being the more specific type.
     *
     * <p>Renders its own standalone page rather than {@code layout :: page(...)} — see
     * {@code error/not-invited.html}'s own comment: this person has no account for an agency to
     * hang a sidebar off. No {@code currentPath} needed here for that reason, unlike its two
     * siblings below.
     */
    @ExceptionHandler(NotInvitedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String notInvited() {
        return "error/not-invited";
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String refused(HttpServletRequest request, Model model) {
        currentPath(request, model);
        return "error/forbidden";
    }

    /**
     * Not a 403. A person who has been invited but not yet added belongs to no agency, and that is
     * an ordinary state of the product rather than a refusal — so it renders a screen that explains
     * it. The response carries no agency's data, so nothing is opened by answering 200.
     */
    @ExceptionHandler(NoAgencyException.class)
    @ResponseStatus(HttpStatus.OK)
    public String noAgencyYet(HttpServletRequest request, Model model) {
        currentPath(request, model);
        return "error/no-agency";
    }

    /**
     * {@code error/forbidden.html} and {@code error/no-agency.html} both render through
     * {@code layout :: page(...)}, and its sidebar reads {@code currentPath} to mark its own
     * active item. That attribute normally comes from {@link ActiveAgencyAdvice}, a
     * {@code @ModelAttribute} method — but Spring does not run a {@code @ControllerAdvice}'s
     * {@code @ModelAttribute} methods for an {@code @ExceptionHandler} invocation the way it does
     * for an ordinary one, so without this the attribute is silently absent on exactly the two
     * screens a refused or not-yet-onboarded person actually reaches.
     * {@code NoAgencyScreenTest.aUserWithNoMembershipIsToldSoRatherThanRefused} asserts the
     * sidebar's active class actually lands on {@code error/no-agency}, which is what would have
     * caught this missing before it shipped rather than after.
     */
    private static void currentPath(HttpServletRequest request, Model model) {
        model.addAttribute("currentPath", request.getRequestURI());
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

    /**
     * Same treatment {@code ContactsAdvice} gives these for the REST controllers, and the same
     * undifferentiated 404 a unit id this workspace has never heard of already gets: no body,
     * because unknown and foreign must stay indistinguishable, and a distinct status or a detailed
     * page would let a caller learn which one it was. Screen-scoped rather than left to
     * {@code ContactsAdvice} because that advice is scoped to the REST controllers and answers with
     * JSON — a screen needs the same status from its own layer, not a widened REST advice.
     */
    @ExceptionHandler({NoSuchContactException.class, NoSuchInterestException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void notFound() {
    }

    /**
     * Same treatment {@link pl.najem.pm.adapter.rest.PmExceptionHandler} gives this for PM's REST
     * controllers: 404, not 403, because a caller must not learn that another agency's id exists.
     * Screen-scoped for the same reason {@link #notFound()} is — that advice answers JSON and is
     * scoped to the REST controllers, so a screen needs the same status from its own layer.
     *
     * <p>{@link TimelineScreenController#cancellableId} still catches this exception itself for the
     * GET that renders the timeline, so an unknown or foreign tenancy id there keeps rendering an
     * ordinary empty history rather than reaching this handler. This handler is for everything else
     * a PM service can raise it from — starting with the POST to {@code /tenancies/{id}/cancel}.
     */
    @ExceptionHandler(UnknownInThisWorkspaceException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void unknownInWorkspace() {
    }

    /**
     * The hidden {@code contactId} field is not something a manager types, but it is a value posted
     * by whatever the browser sent — double back, a stale bookmark, devtools — so a value that does
     * not parse as a UUID is the poster's mistake, not this application's. See
     * {@link UnitScreenController#chosen}.
     */
    @ExceptionHandler(InvalidContactIdException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void badContactId() {
    }

    /**
     * The interest exists and is yours, but has already been withdrawn or converted — most often a
     * stale tab, or the back button after reserving. A 404 would be false, because the caller can
     * see it on their own screen.
     */
    @ExceptionHandler(InterestNotActiveException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void interestClosed() {
    }

    @ExceptionHandler(DuplicatePartyException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void duplicateParty() {
    }

    /**
     * The cancel button is not offered for a tenancy that has moved past RESERVED, but a stale tab
     * can still post to it. Mapped by its own type, deliberately not a bare
     * {@code IllegalStateException} — that would turn every other invariant this aggregate enforces
     * into a tidy 409 as well, including ones that mean this application has a bug.
     */
    @ExceptionHandler(NotReservedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void notReserved() {
    }
}
