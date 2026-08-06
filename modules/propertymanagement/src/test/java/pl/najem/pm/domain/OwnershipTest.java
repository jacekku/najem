package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who is allowed to change a property or a unit, asked of the thing itself.
 *
 * <p>{@code WorkspaceGuardTest} proves the same refusals against the projection tables and a
 * database, for the endpoints that still check that way. These are the rules themselves, on the
 * record rather than on a copy of it, and they run in milliseconds.
 */
class OwnershipTest {

    private final UUID propertyId = UUID.randomUUID();
    private final UUID unitId = UUID.randomUUID();
    private final UUID agencyA = UUID.randomUUID();
    private final UUID agencyB = UUID.randomUUID();

    private Property property() {
        return Property.from(Property.create(propertyId, agencyA, "Testowa 1, Kraków",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))));
    }

    private Unit unit() {
        return Unit.from(Unit.add(unitId, agencyA, propertyId, "M1", new BigDecimal("2500")));
    }

    @Test
    void theOwningAgencyMayAct() {
        assertThatCode(() -> property().requireOwnedBy(agencyA)).doesNotThrowAnyException();
        assertThatCode(() -> unit().requireOwnedBy(agencyA)).doesNotThrowAnyException();
    }

    @Test
    void anotherAgencyMayNot() {
        assertThatThrownBy(() -> property().requireOwnedBy(agencyB))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> unit().requireOwnedBy(agencyB))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
    }

    /**
     * The message says "not found", never "not yours". Confirming that someone else's unit id is
     * real is a disclosure on its own, so the two cases are deliberately one answer —
     * {@link UnknownInThisWorkspaceException} exists to keep them indistinguishable, and a message
     * that named the owner would undo that.
     */
    @Test
    void theRefusalDoesNotSayWhoDoesOwnIt() {
        assertThatThrownBy(() -> unit().requireOwnedBy(agencyB))
            .hasMessageContaining("Not found in this workspace")
            .hasMessageContaining(unitId.toString())
            .hasMessageNotContaining(agencyA.toString());
    }

    /**
     * A null caller is refused rather than compared. The old guard made the same point in SQL — it
     * asked "does a row with this id exist in this workspace" instead of fetching the workspace and
     * comparing, so a null could never accidentally equal a null. Here the equality is in Java and
     * {@code null.equals} would throw rather than pass, but relying on that is relying on an
     * accident; the check is explicit.
     */
    @Test
    void aCallerWithNoWorkspaceIsRefused() {
        assertThatThrownBy(() -> unit().requireOwnedBy(null))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> property().requireOwnedBy(null))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
    }

    /**
     * An aggregate no event has created owns nothing, so nobody may act on it. This is the case the
     * projection check answered by finding no row; rebuilding an empty stream has to answer it the
     * same way rather than comparing null to null and agreeing.
     */
    @Test
    void anAggregateThatNoEventHasCreatedIsOwnedByNobody() {
        assertThatThrownBy(() -> Unit.from(List.of()).requireOwnedBy(agencyA))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> Property.from(List.of()).requireOwnedBy(agencyA))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> Unit.from(List.of()).requireOwnedBy(null))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
    }
}
