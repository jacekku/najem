package pl.najem.um.adapter.rest;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.najem.um.application.ActingCaller;

import java.util.UUID;

/**
 * Fills an {@link ActingUser} parameter from the security context.
 *
 * <p>Delegates to {@link ActingCaller} rather than resolving anything itself, for the reason
 * {@code ApiWorkspaceArgumentResolver} delegates to {@code WebWorkspaceResolver}: the screens, the
 * API and the {@code @PreAuthorize} expressions must agree about who is acting, and two
 * implementations of that would agree on the day they were written and drift afterwards — silently,
 * because each would have its own tests. That drift is not hypothetical here; it is what this whole
 * change is undoing.
 */
@Component
public class ActingUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final ActingCaller caller;

    public ActingUserArgumentResolver(ActingCaller caller) {
        this.caller = caller;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ActingUser.class)
            && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        return caller.actingUserId();
    }
}
