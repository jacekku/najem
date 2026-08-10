package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The owner list as it survives a round trip through the query string.
 *
 * <p>Fast tier, no Spring. Mirrors {@link PartiesDraft} and refuses the same two things for the
 * same reasons — a malformed id must not silently vanish, because a property created with fewer
 * owners than the manager saw on screen is a wrong record that looks like a successful write.
 */
class OwnerDraftTest {

    private static final String ANNA = "11111111-1111-4111-8111-111111111111";
    private static final String PIOTR = "22222222-2222-4222-8222-222222222222";

    @Test
    void nooneDraftedIsAnEmptyList() {
        assertThat(OwnerDraft.of(null).owners()).isEmpty();
        assertThat(OwnerDraft.of(List.of()).owners()).isEmpty();
    }

    /** Order is the order they were added — it is what the share inputs line up against. */
    @Test
    void draftedOwnersKeepTheOrderTheyWereAddedIn() {
        assertThat(OwnerDraft.of(List.of(ANNA, PIOTR)).owners())
            .containsExactly(UUID.fromString(ANNA), UUID.fromString(PIOTR));
    }

    /**
     * One person owns one combined share, not two entries a manager then has to notice sum wrongly.
     */
    @Test
    void thesamePersonCannotOwnTwoShares() {
        assertThatThrownBy(() -> OwnerDraft.of(List.of(ANNA, PIOTR, ANNA)))
            .isInstanceOf(DuplicatePartyException.class);
    }

    @Test
    void agarbledIdIsRefusedRatherThanSkipped() {
        assertThatThrownBy(() -> OwnerDraft.of(List.of(ANNA, "not-a-uuid")))
            .isInstanceOf(InvalidContactIdException.class);
    }

    @Test
    void ablankIdIsAsMuchAMistakeAsAGarbledOne() {
        assertThatThrownBy(() -> OwnerDraft.of(List.of("   ")))
            .isInstanceOf(InvalidContactIdException.class);
    }
}
