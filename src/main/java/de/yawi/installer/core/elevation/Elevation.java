package de.yawi.installer.core.elevation;

import de.yawi.installer.core.engine.ExecutionPlan;
import de.yawi.installer.core.platform.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The installer's elevation set-up as one object the {@code InstallRunner}
 * takes: how to tell what needs elevating, how to start the elevated
 * process and with which rights prompt. {@link #forRuntime()} is the real
 * thing; {@link #direct()} runs the child without elevation and {@link #none()}
 * refuses, both for tests.
 */
public final class Elevation {

    private final Function<Platform, Optional<ElevationStrategy>> strategies;
    private final Supplier<List<String>> command;
    private final Optional<ElevationNeed.Mode> forcedMode;

    private Elevation(Function<Platform, Optional<ElevationStrategy>> strategies, Supplier<List<String>> command,
                      Optional<ElevationNeed.Mode> forcedMode) {
        this.strategies = strategies;
        this.command = command;
        this.forcedMode = forcedMode;
    }

    /** The strategies of this machine ({@link ElevationStrategy#select}) and this installer's own command. */
    public static Elevation forRuntime() {
        return new Elevation(ElevationStrategy::select, SelfCommand::resolve, Optional.empty());
    }

    /** The child runs as the caller, unelevated: the whole hand-over without a rights prompt. */
    public static Elevation direct() {
        return new Elevation(p -> Optional.of(new Strategies.Direct()), SelfCommand::resolve, Optional.empty());
    }

    /** Elevation is not available; a run that needs it fails with exit 13. */
    public static Elevation none() {
        return new Elevation(p -> Optional.empty(), SelfCommand::resolve, Optional.empty());
    }

    /**
     * For tests: {@link #assess} answers {@code mode} whatever plan and
     * destination say ({@code WHOLE} of a temp folder cannot be provoked
     * otherwise). The step ids follow from the mode.
     */
    public Elevation forcing(ElevationNeed.Mode mode) {
        return new Elevation(strategies, command, Optional.of(mode));
    }

    public ElevationNeed assess(ExecutionPlan plan, Path destination, Platform platform) {
        if (forcedMode.isPresent()) {
            return switch (forcedMode.get()) {
                case NONE -> ElevationNeed.NONE;
                case WHOLE -> ElevationPlanner.assess(plan, true, false);
                case PARTIAL -> ElevationPlanner.assess(plan, false, false);
            };
        }
        return ElevationPlanner.assess(plan, destination, platform);
    }

    Optional<ElevationStrategy> strategy(Platform platform) {
        return strategies.apply(platform);
    }

    List<String> command() {
        return command.get();
    }
}
