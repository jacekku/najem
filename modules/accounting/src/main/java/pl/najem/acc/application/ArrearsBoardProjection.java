package pl.najem.acc.application;

import pl.najem.acc.domain.ArrearsColour;

import java.util.List;
import java.util.UUID;

/**
 * Reading the arrears board.
 *
 * <p>A projection, not a repository: acc_tenancy_status holds nothing that is not already in
 * acc_charge, and can be dropped and rebuilt from it (rule A7).
 *
 * <p><strong>Separate from {@link ArrearsStandingProjection} on purpose.</strong> That one writes
 * and is what services hold; this one reads and is what a screen holds. Folding them into one port
 * would put a read of the colour within reach of every service that refreshes it, and a service
 * reading the colour to decide something is the exact failure the projection naming exists to
 * prevent: at that moment the board stops being derived and becomes an opinion. Only the record may
 * be read to make a decision.
 *
 * <p>The colour comes back as the enum rather than the string on the row. A screen that reads
 * {@code "brightRed"} as an opaque token can render it, but it cannot be checked against the set of
 * colours that actually exist, and a colour this module stopped emitting would go on rendering
 * until somebody noticed the legend was wrong.
 */
public interface ArrearsBoardProjection {

    /**
     * One tenancy's standing.
     *
     * @param fullPeriodsInArrears whole payment periods the tenant has been in delay for. The colour
     *                             says the art. 11 clock is running; this says how far it has run,
     *                             and termination becomes available at three. A screen that renders
     *                             the colour alone can tell a manager something is wrong but not
     *                             what they are entitled to do about it.
     */
    record Row(UUID tenancyId, ArrearsColour colour, int fullPeriodsInArrears) {}

    List<Row> forWorkspace(UUID workspaceId);
}
