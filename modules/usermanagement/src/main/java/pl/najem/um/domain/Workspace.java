package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Event-sourced aggregate; stream type "Workspace". The agency that owns properties, ledgers and people. */
public class Workspace {

    private UUID id;
    private String name;

    private Workspace() {}

    public static List<Object> create(UUID workspaceId, String name, LocalDate on) {
        requireName(name);
        return List.of(new WorkspaceCreated(workspaceId, name.trim(), on));
    }

    public List<Object> rename(String newName, LocalDate on) {
        requireName(newName);
        return List.of(new WorkspaceRenamed(id, newName.trim(), on));
    }

    public static Workspace from(List<Object> events) {
        var workspace = new Workspace();
        events.forEach(workspace::apply);
        return workspace;
    }

    private void apply(Object event) {
        if (event instanceof WorkspaceCreated e) {
            id = e.workspaceId();
            name = e.name();
        } else if (event instanceof WorkspaceRenamed e) {
            name = e.name();
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("workspace name must not be blank");
        }
    }

    public UUID id() { return id; }

    public String name() { return name; }
}
