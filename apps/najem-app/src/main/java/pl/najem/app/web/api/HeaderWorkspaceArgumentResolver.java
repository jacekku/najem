package pl.najem.app.web.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.najem.contracts.web.ActingWorkspace;

import java.util.UUID;

/**
 * Reads the workspace from {@code X-Workspace-Id}. <b>For tests, and only for tests.</b>
 *
 * <p>This is the whole of what remains of the header. It exists because the end-to-end suite drives
 * a real application over HTTP with no identity provider in front of it: there is no token, so there
 * is no subject, so there are no memberships to resolve a workspace from. A suite that had to stand
 * up Keycloak to assert that a tenancy can be created would be testing the login rather than the
 * tenancy.
 *
 * <p>It is registered only when {@link WorkspaceHeaderEnabled} says so, which requires
 * {@code najem.test.workspace-header=true} to be set on purpose. Nothing packaged sets it, and a
 * test asserts that stays true.
 *
 * <p><b>It does not check membership, and that is not an oversight.</b> When this resolver is
 * active the deployment has already declared it trusts the caller's word about who they are; adding
 * a check here would suggest the header is safe with one, which is the belief the production
 * resolver exists to remove. Where a test needs the real rule, it runs without this bean.
 */
public class HeaderWorkspaceArgumentResolver implements ApiWorkspaceResolver {

    static final String HEADER = "X-Workspace-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ActingWorkspace.class)
            && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        var servletRequest = request.getNativeRequest(HttpServletRequest.class);
        String named = servletRequest == null ? null : servletRequest.getHeader(HEADER);
        if (named == null || named.isBlank()) {
            // No fallback workspace, even here. A missing header used to resolve to a DEV constant
            // and that is how writes landed in books nobody named; the tests that proved it gone
            // (DevWorkspaceGoneTest, three modules) are still watching. Missing means missing.
            throw new MissingWorkspaceHeaderException("no " + HEADER + " on a request that needs one");
        }
        try {
            return UUID.fromString(named.trim());
        } catch (IllegalArgumentException malformed) {
            throw new MissingWorkspaceHeaderException(HEADER + " is not a workspace id");
        }
    }
}
