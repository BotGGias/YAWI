/**
 * Headless installer core: manifest, platform abstraction, download, engine,
 * integrity, state, i18n and error handling.
 *
 * <p>Nothing below this package may depend on JavaFX. The core has to run
 * without a display so that the silent mode ({@code de.yawi.installer.cli}, E15)
 * can reuse it unchanged and the engine can be tested headless (E18). Progress
 * is reported through callbacks; bringing those onto the JavaFX application
 * thread is the sole responsibility of {@code de.yawi.installer.ui}.
 *
 * <p>Sub-packages arrive with their epics, see
 * {@code docs/backlog/architecture.md}.
 */
package de.yawi.installer.core;
