package pl.najem.um.application;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code um_workspace}: the agency's name, so a screen can show one. Derivable from the
 * {@code Workspace} stream ({@code WorkspaceCreated}, {@code WorkspaceRenamed}).
 */
public interface WorkspaceProjection {

    void create(UUID workspaceId, String name, LocalDate on);

    void rename(UUID workspaceId, String name);
}
