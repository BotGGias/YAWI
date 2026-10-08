package de.yawi.installer.core.manifest;

import java.util.List;

/**
 * One process invocation as an argument list.
 *
 * <p>Every {@code <arg>} becomes one process argument; nothing is ever joined
 * into a shell line.
 */
public record Command(List<String> args) {

    public Command {
        args = List.copyOf(args);
        if (args.isEmpty()) {
            throw new IllegalArgumentException("command needs at least one arg");
        }
    }

    public String executable() {
        return args.get(0);
    }
}
