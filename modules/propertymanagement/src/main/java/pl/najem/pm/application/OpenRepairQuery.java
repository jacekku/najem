package pl.najem.pm.application;

import java.util.List;
import java.util.UUID;

/** What is still open on this agency's assets, oldest first. */
public interface OpenRepairQuery {

    /**
     * Open repairs in one workspace, ordered by when they were reported.
     *
     * <p>Oldest first rather than newest: the list exists so that nothing is forgotten, and the
     * thing most likely to have been forgotten is the one that has been waiting longest.
     */
    List<OpenRepair> openRepairs(UUID workspaceId);
}
