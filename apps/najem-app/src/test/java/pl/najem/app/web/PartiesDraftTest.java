package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PartiesDraft}, tested without booting anything — the {@code SearchGroupingTest}
 * precedent.
 */
class PartiesDraftTest {

    @Test
    void repeatedParametersBecomeTheTwoLists() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        var draft = PartiesDraft.of(List.of(a.toString()), List.of(b.toString()));

        assertThat(draft.tenants()).containsExactly(a);
        assertThat(draft.guarantors()).containsExactly(b);
    }

    @Test
    void aMalformedIdIsRefusedRatherThanDropped() {
        assertThatThrownBy(() -> PartiesDraft.of(List.of("not-a-uuid"), List.of()))
            .isInstanceOf(InvalidContactIdException.class);
    }

    @Test
    void theSamePersonCannotBeAddedTwice() {
        UUID a = UUID.randomUUID();

        assertThatThrownBy(() -> PartiesDraft.of(List.of(a.toString(), a.toString()), List.of()))
            .isInstanceOf(DuplicatePartyException.class);
    }

    @Test
    void theSamePersonCannotGuaranteeTheirOwnTenancy() {
        UUID a = UUID.randomUUID();

        assertThatThrownBy(() -> PartiesDraft.of(List.of(a.toString()), List.of(a.toString())))
            .isInstanceOf(DuplicatePartyException.class);
    }

    @Test
    void absentParametersAreEmptyLists() {
        var draft = PartiesDraft.of(null, null);

        assertThat(draft.tenants()).isEmpty();
        assertThat(draft.guarantors()).isEmpty();
    }
}
