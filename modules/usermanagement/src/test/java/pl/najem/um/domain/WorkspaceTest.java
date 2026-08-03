package pl.najem.um.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);

    @Test
    void creationRecordsTheAgencyName() {
        var workspaceId = UUID.randomUUID();

        var events = Workspace.create(workspaceId, "Agencja Krakowska", TODAY);

        assertThat(events).containsExactly(new WorkspaceCreated(workspaceId, "Agencja Krakowska", TODAY));
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> Workspace.create(UUID.randomUUID(), "  ", TODAY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renamingReplaysIntoTheNewName() {
        var workspaceId = UUID.randomUUID();
        var created = Workspace.create(workspaceId, "Stara Nazwa", TODAY);
        var workspace = Workspace.from(created);

        var events = workspace.rename("Nowa Nazwa", TODAY);

        assertThat(events).containsExactly(new WorkspaceRenamed(workspaceId, "Nowa Nazwa", TODAY));
        assertThat(Workspace.from(java.util.stream.Stream.concat(created.stream(), events.stream()).toList())
            .name()).isEqualTo("Nowa Nazwa");
    }
}
