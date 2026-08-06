package pl.najem.app.web.api;

import org.springframework.web.method.support.HandlerMethodArgumentResolver;

/**
 * How a module's API gets its workspace — the real way, or the test one.
 *
 * <p>A type of its own so the wiring can ask for it by type. {@code WebWorkspaceArgumentResolver}
 * is also a {@link HandlerMethodArgumentResolver}, so asking for that interface would be ambiguous,
 * and resolving the ambiguity with a bean name would make the choice depend on a string.
 */
public interface ApiWorkspaceResolver extends HandlerMethodArgumentResolver {
}
