/**
 * Optional privilege elevation (E12): deciding whether a run needs
 * administrator rights ({@link de.yawi.installer.core.elevation.ElevationPlanner}),
 * handing the affected part of the plan to a second, elevated process of this
 * installer ({@link de.yawi.installer.core.elevation.ElevatedRun} on the
 * parent side, {@link de.yawi.installer.core.elevation.ElevatedWorker} in the
 * child), and the system's own rights prompt per operating system
 * ({@link de.yawi.installer.core.elevation.ElevationStrategy}). The two
 * processes talk through files in a private hand-over directory only, so the
 * same code works with {@code pkexec}, {@code sudo}, UAC and {@code osascript}.
 * Headless, no JavaFX.
 */
package de.yawi.installer.core.elevation;
