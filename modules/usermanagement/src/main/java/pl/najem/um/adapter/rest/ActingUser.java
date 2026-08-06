package pl.najem.um.adapter.rest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Hands a controller the NAJEM user id of whoever is calling.
 *
 * <p>The counterpart of {@code @ActingWorkspace} and adopted for the same reason: controllers used
 * to take a {@code Jwt} and resolve the caller themselves, which meant every endpoint could get it
 * wrong independently, and one of them — the browser path — did. A parameter the framework fills is
 * one nobody can forget to fill.
 *
 * <p><b>Identity only. This annotation authorizes nothing.</b> Whether the caller may perform the
 * action is decided by {@code @PreAuthorize} on the service, deliberately kept a separate mechanism:
 * an argument resolver that also refused requests would be doing authorization as a side effect of
 * binding a parameter, in a place no one looks for it.
 *
 * <p>Not in {@code contracts.web} beside {@code @ActingWorkspace}, because no other module needs it.
 * Every other module is handed a workspace and never asks who the person is; this one is the module
 * that manages people, so the question is its own.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ActingUser {
}
