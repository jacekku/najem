package pl.najem.um.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.Role;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * That the authorization rules are actually <em>attached</em> to the operations they govern.
 *
 * <p>{@link ActingCallerTest} proves the rules decide correctly. It cannot prove they run: it calls
 * the services directly, and {@code @PreAuthorize} is proxy-based, so a direct call — or a service
 * built with {@code new}, which every fixture in this repository does — is unauthorized and green.
 * <b>A passing fast test is not evidence that authorization works.</b> This file is the evidence,
 * and it boots a real Spring context to get real proxies.
 *
 * <p>It is deliberately a plain {@code AnnotationConfigApplicationContext} rather than
 * {@code @SpringBootTest}: what is under test is that method security is honoured on these beans,
 * and booting the application would drag in a database, an issuer and a web layer to prove
 * something none of them are involved in.
 *
 * <p>The second half is a tripwire. {@code @PreAuthorize} is one line and deleting it leaves
 * everything compiling, every fast test green, and every endpoint open — the same failure mode
 * {@code WorkspaceBoundaryTest} exists for in property-management. {@link #everyWorkspaceScopedWriteIsGuarded}
 * fails when a new workspace-scoped operation is added without a rule. <b>If it goes red, add the
 * annotation. Do not add the method to an exclusion list.</b>
 */
class MethodSecurityWiringTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate ON = LocalDate.of(2026, 8, 6);

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    /** The services under a real proxy, with a caller whose verdict the test dictates. */
    private MembershipService memberships(boolean isAdmin) {
        var caller = mock(ActingCaller.class);
        when(caller.isAdminOf(WORKSPACE)).thenReturn(isAdmin);
        context = new AnnotationConfigApplicationContext();
        context.registerBean("caller", ActingCaller.class, () -> caller);
        context.registerBean(EventStore.class, () -> mock(EventStore.class));
        // Stubbed to nothing on purpose: a refused call must not reach the store at all, so these
        // exist only to let the bean be constructed.
        context.registerBean(MembershipProjection.class, () -> mock(MembershipProjection.class));
        context.register(MethodSecurity.class, MembershipService.class);
        context.refresh();
        return context.getBean(MembershipService.class);
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Test
    void anonAdminIsRefusedARoleChange() {
        assertThatThrownBy(() -> memberships(false).changeRole(WORKSPACE, USER, Role.MANAGER, ON))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anonAdminIsRefusedARemoval() {
        assertThatThrownBy(() -> memberships(false).remove(WORKSPACE, USER, ON))
            .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The other half of the claim. Without this, a rule that refused <em>everyone</em> would pass
     * the two tests above and take the feature out silently.
     */
    @Test
    void anadminIsNotRefused() {
        // Not "throws nothing": the store and template here are mocks, so the call fails further in.
        // What matters is that it got past the rule, so the failure is anything but a refusal.
        var thrown = catchThrowable(() -> memberships(true).changeRole(WORKSPACE, USER, Role.MANAGER, ON));

        assertThat(thrown).isNotInstanceOf(AccessDeniedException.class);
    }

    /**
     * Every workspace-scoped write on these services carries a rule.
     *
     * <p>The predicate is "first parameter is the workspace id", because that is what makes an
     * operation something a caller does <em>to an agency</em> — and it is the shape
     * {@code @PreAuthorize("@caller.isAdminOf(#workspaceId)")} binds to. Reads are excluded: they
     * are already scoped by the workspace they are handed, and a caller who cannot name a workspace
     * cannot read another's.
     */
    @Test
    void everyWorkspaceScopedWriteIsGuarded() {
        var unguarded = List.of(MembershipService.class, InvitationService.class).stream()
            .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods()))
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .filter(MethodSecurityWiringTest::takesAWorkspaceFirst)
            .filter(m -> !m.isAnnotationPresent(
                org.springframework.security.access.prepost.PreAuthorize.class))
            .map(m -> m.getDeclaringClass().getSimpleName() + "." + m.getName())
            .toList();

        assertThat(unguarded)
            .as("workspace-scoped operations with no @PreAuthorize — add the rule, do not exclude")
            .isEmpty();
    }

    /**
     * The companion to the tripwire, and the reason it is not theatre: a predicate that matches
     * nothing is indistinguishable from a predicate that is satisfied (rule 20). If a refactoring
     * renames {@code workspaceId} or reorders the parameters, the check above silently stops
     * checking, and this is what says so.
     */
    @Test
    void thetripwireStillMatchesSomething() {
        var guarded = List.of(MembershipService.class, InvitationService.class).stream()
            .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods()))
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .filter(MethodSecurityWiringTest::takesAWorkspaceFirst)
            .toList();

        assertThat(guarded)
            .as("the tripwire's predicate matches no method, so it can no longer fail")
            .hasSize(4);
    }

    private static boolean takesAWorkspaceFirst(Method method) {
        var parameters = method.getParameters();
        return parameters.length > 0
            && parameters[0].getType().equals(UUID.class)
            && parameters[0].getName().equals("workspaceId");
    }
}
