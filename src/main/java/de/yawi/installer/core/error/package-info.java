/**
 * Error classes of the installer.
 *
 * <p>Every failure that reaches the user or the exit code is an
 * {@link de.yawi.installer.core.error.InstallerException} with an
 * {@link de.yawi.installer.core.error.ErrorCode}. The exception carries the
 * technical text for the log ({@code getMessage()}) and the bundle key plus
 * arguments for the user's text, which is resolved only when shown - the UI
 * and the silent mode render the same exception in their own
 * language. Checked exceptions from the JDK or third party code are translated
 * at the boundary where they occur ({@link de.yawi.installer.core.error.Errors}),
 * never passed on.
 */
package de.yawi.installer.core.error;
