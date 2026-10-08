/**
 * System integration (E13): where a shortcut goes on each operating system
 * and what it contains. Pure building blocks - paths, file contents, script
 * texts, a best-effort call to the desktop's cache tool - with no knowledge
 * of the engine or the installation record; the engine's {@code ShortcutStep}
 * does the writing and the recording.
 */
package de.yawi.installer.core.integration;
