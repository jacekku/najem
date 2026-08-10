package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a property and a unit must have to exist at all.
 *
 * <p>Fast tier: no container, no Spring. These are the guards that make every screen downstream
 * able to render a property without checking for nulls first, so they are worth a millisecond.
 */
class PropertyAndUnitCreationTest {

    private static final UUID PROPERTY = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();

    @Test
    void apropertyWithoutAnAddressIsNotAProperty() {
        assertThatThrownBy(() -> Property.create(PROPERTY, WORKSPACE, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("address");
    }

    /**
     * Whitespace, not only null. A form posts "   " when somebody tabs through the field, and a
     * property addressed with three spaces renders as a blank clickable row on the portfolio board.
     */
    @Test
    void anaddressOfNothingButSpacesIsNotAnAddress() {
        assertThatThrownBy(() -> Property.create(PROPERTY, WORKSPACE, "   ", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("address");
    }

    @Test
    void apropertyWithAnAddressIsCreated() {
        var events = Property.create(PROPERTY, WORKSPACE, "Krucza 12/4, Warszawa", List.of());

        assertThat(events).hasSize(1);
        assertThat(Property.from(events).address()).isEqualTo("Krucza 12/4, Warszawa");
    }

    @Test
    void aunitWithoutANameIsNotAUnit() {
        assertThatThrownBy(() -> Unit.add(UNIT, WORKSPACE, PROPERTY, "  ", new BigDecimal("2500")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void aunitWithoutABaseRentIsNotAUnit() {
        assertThatThrownBy(() -> Unit.add(UNIT, WORKSPACE, PROPERTY, "M2", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("base rent");
    }

    @Test
    void anegativeBaseRentIsRefused() {
        assertThatThrownBy(() -> Unit.add(UNIT, WORKSPACE, PROPERTY, "M2", new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("base rent");
    }

    /**
     * Zero is allowed and this is not an oversight. A caretaker's flat let rent-free as part of a
     * job is a real arrangement, and the domain has no business having an opinion about it. The
     * guard refuses absent and negative, which are mistakes; it does not refuse cheap.
     */
    @Test
    void arentFreeUnitIsAllowed() {
        var events = Unit.add(UNIT, WORKSPACE, PROPERTY, "Portiernia", BigDecimal.ZERO);

        assertThat(Unit.from(events).baseRent()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * The empty-input case, and it is not hypothetical. {@code @RequestParam List<BigDecimal>}
     * binds a present-but-empty {@code share=} to a <em>null element</em>, so the list is the right
     * length and the controller's length check waves it through. Without this guard the null goes
     * into the stream and {@code warnings()} throws inside a render — a 500 for a mistyped form.
     */
    @Test
    void anownerWithNoShareIsRefused() {
        var owners = new java.util.ArrayList<Owner>();
        owners.add(new Owner(UUID.randomUUID(), new BigDecimal("50")));
        owners.add(new Owner(UUID.randomUUID(), null));

        assertThatThrownBy(() -> Property.create(PROPERTY, WORKSPACE, "Krucza 12", owners))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("share");
    }

    @Test
    void anegativeShareIsRefused() {
        assertThatThrownBy(() -> Property.create(PROPERTY, WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("-10")),
            new Owner(UUID.randomUUID(), new BigDecimal("110")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("share");
    }

    /**
     * The <em>other</em> writer of the owner list, and the reason the guard is one method.
     *
     * <p>{@code changeOwnership} has no caller today, which is exactly why it was unguarded and
     * exactly why that is worth a test: the argument for putting the check in the domain — a null
     * stake in the stream cannot be corrected by any screen — does not care which command wrote it.
     * The day this grows a screen, the guard is already there rather than being remembered.
     */
    @Test
    void changingOwnershipToAStakeholderWithNoShareIsRefusedToo() {
        var property = Property.from(Property.create(PROPERTY, WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))));

        var replacement = new java.util.ArrayList<Owner>();
        replacement.add(new Owner(UUID.randomUUID(), null));

        assertThatThrownBy(() -> property.changeOwnership(replacement))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("share");
    }

    @Test
    void changingOwnershipToANegativeShareIsRefusedToo() {
        var property = Property.from(Property.create(PROPERTY, WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))));

        assertThatThrownBy(() -> property.changeOwnership(List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("-1")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("share");
    }

    /** Zero is a share somebody legitimately holds on paper; the guard refuses absent and negative. */
    @Test
    void ashareOfZeroIsAllowed() {
        var property = Property.from(Property.create(PROPERTY, WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), BigDecimal.ZERO),
            new Owner(UUID.randomUUID(), new BigDecimal("100")))));

        assertThat(property.warnings().messages()).isEmpty();
    }

    @Test
    void ownersTotallingOneHundredRaiseNoWarning() {
        var property = Property.from(Property.create(PROPERTY, WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("50")),
            new Owner(UUID.randomUUID(), new BigDecimal("50")))));

        assertThat(property.warnings().messages()).isEmpty();
    }

    /**
     * The message must name the actual total. "Shares are wrong" tells a manager to go and add up
     * three numbers the system already added up.
     */
    @Test
    void ownersTotallingNinetyRaiseAWarningNamingNinety() {
        var property = Property.from(Property.create(PROPERTY, WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("60")),
            new Owner(UUID.randomUUID(), new BigDecimal("30")))));

        assertThat(property.warnings().messages()).singleElement().asString().contains("90");
    }
}
