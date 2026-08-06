package pl.najem.app.web.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.najem.app.web.ChoiceRequiredException;
import pl.najem.app.web.NoAgencyException;
import pl.najem.app.web.WebWorkspaceResolver;
import pl.najem.contracts.web.ActingWorkspace;

import java.util.UUID;

/**
 * Hands a module's REST controller the workspace its caller acts in, derived from the caller.
 *
 * <p>Every module's API used to take {@code X-Workspace-Id} and act on whatever it said, with an
 * interceptor in this application checking afterwards that the caller belonged to it. The check was
 * sound; the claim was the problem. Here there is nothing to check, because the workspace is read
 * off the caller's memberships rather than off the request — <b>a caller who cannot state a
 * workspace cannot state the wrong one</b>, and a check that cannot fail is one fewer thing to keep
 * correct as endpoints are added.
 *
 * <p><b>Deliberately delegates to {@link WebWorkspaceResolver}</b> rather than resolving again. The
 * screens and the API must agree about who is acting and where; two implementations of that would
 * agree on the day they were written and drift afterwards, and the drift would be silent because
 * each has its own tests. What differs between screen and API is only how a failure is reported,
 * so only that is duplicated.
 */
public class ApiWorkspaceArgumentResolver implements ApiWorkspaceResolver {

    private final WebWorkspaceResolver resolver;

    public ApiWorkspaceArgumentResolver(WebWorkspaceResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ActingWorkspace.class)
            && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        var servletRequest = request.getNativeRequest(HttpServletRequest.class);
        try {
            // getSession(false), as on the screen side: an API call must not mint a session. A
            // browser calling /api/** from a screen already has one and its chosen agency is
            // honoured; a bearer-token client has none and resolves from memberships alone.
            return resolver.resolve(jwt(), servletRequest == null ? null : servletRequest.getSession(false))
                .workspaceId();
        } catch (NoAgencyException noAgency) {
            // A screen answers this with a page explaining it; an API caller gets a refusal,
            // because there is no agency whose data could be served and no page to explain it to.
            throw new NoWorkspaceException(noAgency.getMessage());
        } catch (ChoiceRequiredException choice) {
            // Rule 7 in its most literal form: several agencies, none named, and picking one would
            // be a default deciding WHOSE books a write lands in. A misdirected write is sticky —
            // uniqueness is per workspace — so this refuses instead of guessing.
            throw new WorkspaceChoiceRequiredException(choice.getMessage());
        }
    }

    private static Jwt jwt() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token ? token.getToken() : null;
    }
}
