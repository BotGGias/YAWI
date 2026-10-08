/**
 * The execution engine: turns the manifest's steps into an
 * {@link de.yawi.installer.core.engine.ExecutionPlan} for the selected
 * components and runs it.
 *
 * <p>Headless like the rest of the core. Progress goes out through
 * {@link de.yawi.installer.core.engine.ProgressListener}; the UI is the only
 * layer that knows the JavaFX thread. Cancellation comes in through
 * {@link de.yawi.installer.core.engine.CancellationToken}. Everything a step
 * needs at run time is in the {@link de.yawi.installer.core.engine.ExecutionContext}.
 */
package de.yawi.installer.core.engine;
