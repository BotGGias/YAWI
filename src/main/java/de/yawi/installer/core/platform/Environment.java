package de.yawi.installer.core.platform;

import java.util.Map;
import java.util.Optional;

/**
 * Environment variables and system properties as seen by a {@link Platform}.
 *
 * <p>Injected so that the path logic of every platform can be tested on any
 * machine: {@link #system()} at runtime, {@link #of(Map, Map)} in tests.
 */
public interface Environment {

    Optional<String> env(String name);

    Optional<String> property(String name);

    /** The real process environment. */
    static Environment system() {
        return new Environment() {
            @Override
            public Optional<String> env(String name) {
                return Optional.ofNullable(System.getenv(name));
            }

            @Override
            public Optional<String> property(String name) {
                return Optional.ofNullable(System.getProperty(name));
            }
        };
    }

    /**
     * A fixed environment. Pass a case-insensitive map (e.g.
     * {@code new TreeMap<>(String.CASE_INSENSITIVE_ORDER)}) to mimic Windows.
     */
    static Environment of(Map<String, String> env, Map<String, String> properties) {
        return new Environment() {
            @Override
            public Optional<String> env(String name) {
                return Optional.ofNullable(env.get(name));
            }

            @Override
            public Optional<String> property(String name) {
                return Optional.ofNullable(properties.get(name));
            }
        };
    }
}
