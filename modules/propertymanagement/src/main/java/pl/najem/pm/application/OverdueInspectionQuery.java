package pl.najem.pm.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The read a compliance screen makes: what is past its statutory deadline in this workspace.
 *
 * <p>Separate from {@link InspectionProjection} so that recording an inspection and reading the
 * overdue list are separate capabilities. A service that only writes cannot reach this, which is
 * what stops the derived list being used to decide something.
 *
 * <p>The scope is always a workspace. There is no overload without one — an overdue list is a
 * statutory position, and the module has no caller entitled to every agency's.
 */
public interface OverdueInspectionQuery {

    /**
     * Overdue as of a date, nearest deadline first.
     *
     * <p>Only the latest inspection of each type on each property counts: an annual gas check done
     * in 2026 and again in 2027 is one obligation, not two, and reporting the superseded row would
     * be a false alarm on a compliant property.
     *
     * <p><b>"Latest" here means the latest performed_on, which is not what the aggregate means by
     * it.</b> {@code Property.apply} keeps whichever InspectionCompleted was applied last, i.e. the
     * most recently recorded. The two agree until somebody back-enters an inspection performed
     * before one already recorded, and then they disagree about which sets the deadline.
     * {@code ComplianceDivergenceTest} pins both answers so the difference is visible and cannot
     * drift further; which one is right under art. 62 is not settled here.
     */
    List<OverdueInspection> overdue(UUID workspaceId, LocalDate on);
}
