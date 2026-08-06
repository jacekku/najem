package pl.najem.um.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** In-memory {@link WorkspaceProjection}. Stands in for {@code um_workspace}. */
public class InMemoryWorkspaces implements WorkspaceProjection {

    final Map<UUID, String> names = new LinkedHashMap<>();

    @Override
    public void create(UUID workspaceId, String name, LocalDate on) {
        names.put(workspaceId, name);
    }

    @Override
    public void rename(UUID workspaceId, String name) {
        if (!names.containsKey(workspaceId)) {
            return; // an update matching no row
        }
        names.put(workspaceId, name);
    }
}
