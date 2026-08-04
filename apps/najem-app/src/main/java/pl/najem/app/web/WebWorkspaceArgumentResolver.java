package pl.najem.app.web;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Lets a controller declare {@link WebWorkspace} as a parameter and be handed one already resolved.
 *
 * <p>The point is that there is no other way to get one. A controller cannot construct a
 * {@code WebWorkspace}, cannot read a workspace from a header, and cannot look one up — so a screen
 * either receives a checked workspace or has none.
 */
@Component
public class WebWorkspaceArgumentResolver implements HandlerMethodArgumentResolver {

    private final WebWorkspaceResolver resolver;

    public WebWorkspaceArgumentResolver(WebWorkspaceResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return WebWorkspace.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        var servletRequest = request.getNativeRequest(HttpServletRequest.class);
        return resolver.resolve(jwt(), servletRequest == null ? null : servletRequest.getSession(true));
    }

    private static Jwt jwt() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken token ? token.getToken() : null;
    }
}
