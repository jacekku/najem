package pl.najem.contracts.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the parameter that receives the workspace a request acts in.
 *
 * <p>The value is <b>derived from who the caller is</b> — their signed-in subject and the
 * memberships UserManagement holds for it — and never from anything the caller sends. A request
 * cannot name a workspace; it can only be one, and being one is a fact about the token rather than
 * a field in the request.
 *
 * <p>This replaces the {@code X-Workspace-Id} header parameter, which let any caller assert which
 * agency they were acting in and left the checking to somebody else. That check existed (an
 * interceptor in the composition root) and worked, but it was a second mechanism guarding a claim
 * that never needed to be made: a caller who cannot state a workspace cannot state the wrong one.
 *
 * <p>Declared here in {@code contracts} because every module depends on it and none may depend on
 * usermanagement. The annotation carries no resolution logic — it is a request for one. The
 * composition root supplies the resolver, so a module cannot resolve its own workspace and cannot
 * be wired into an application that has not decided how identity works.
 *
 * <p><b>The header survives in exactly one place:</b> a resolver the tests turn on explicitly. See
 * {@code najem.test.workspace-header}.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActingWorkspace {
}
