package de.yawi.installer.core.elevation;

import de.yawi.installer.core.platform.Platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The system's own way of asking for administrator rights: starts
 * a command elevated and tells, from the exit code, whether the user declined.
 * The installer never sees a password. Implementations are chosen by
 * {@link #select}; {@code -Dyawi.elevation=<name>} forces one, {@code none}
 * switches elevation off, {@code direct} runs the child without elevation
 * (tests, or a developer who is already root).
 */
public interface ElevationStrategy {

    /** What the exit code of the launching process means when the child never reported a result. */
    enum Outcome {
        /** The strategy itself worked; whatever happened is the child's own exit. */
        NORMAL,
        /** The user dismissed the prompt or gave no valid password. */
        DECLINED,
        /** The strategy could not run at all (tool missing, not authorised, agent absent). */
        FAILED
    }

    String name();

    /** Whether this strategy can be tried on this machine, without asking anything yet. */
    boolean available(Platform platform);

    /** Whether the child will run with elevated rights (false only for the direct strategy). */
    default boolean elevates() {
        return true;
    }

    /**
     * Starts {@code command} elevated. Standard output is discarded, standard
     * error is the caller's to read (the strategy's own complaints).
     */
    Process start(List<String> command, Path workingDirectory) throws IOException;

    Outcome classify(int exitCode, String stderrTail);

    /**
     * The first usable strategy for this platform, honouring {@code -Dyawi.elevation}.
     *
     * @return empty if elevation is switched off or nothing is available
     */
    static Optional<ElevationStrategy> select(Platform platform) {
        return Strategies.select(platform, System.getProperty("yawi.elevation"));
    }
}
