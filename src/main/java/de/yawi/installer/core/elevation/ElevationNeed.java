package de.yawi.installer.core.elevation;

import java.util.List;

/**
 * What of a run has to be elevated (E12-S01).
 *
 * @param elevatedStepIds     the steps the elevated process runs, in plan order
 * @param destinationRequires whether the destination itself needs elevation
 *                            (then the whole run does)
 */
public record ElevationNeed(Mode mode, List<String> elevatedStepIds, boolean destinationRequires) {

    public enum Mode {
        /** Nothing needs elevation, or the process already has it. */
        NONE,
        /** Only the {@code elevated="true"} steps run elevated, as one block after the normal steps. */
        PARTIAL,
        /** The destination needs elevation: every step runs in the elevated process. */
        WHOLE
    }

    public static final ElevationNeed NONE = new ElevationNeed(Mode.NONE, List.of(), false);

    public ElevationNeed {
        elevatedStepIds = List.copyOf(elevatedStepIds);
    }

    public boolean required() {
        return mode != Mode.NONE;
    }
}
