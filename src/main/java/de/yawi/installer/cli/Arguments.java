package de.yawi.installer.cli;

import de.yawi.installer.core.error.InvalidArgumentsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The command line, parsed. Every option is {@code --name} or
 * {@code --name=value}; nothing else is accepted, so a typo never silently
 * turns into a default. Unknown options and missing values are an
 * {@link InvalidArgumentsException} (exit code 2), never a stack trace.
 *
 * <p>The options from the update contract ({@code --update}, {@code --cache},
 * {@code --wait-pid}, {@code --relaunch}, {@code --result}) are documented in
 * the launcher's {@code BETRIEB.md}, "Installer-Vertrag (Update)".
 *
 * @param config     {@code --config=} answer file or null
 * @param components explicit {@code --components=} ids, empty if not given
 * @param inputs     {@code --input.<id>=<value>} pairs
 * @param waitPids   {@code --wait-pid=} values in the given order
 * @param manifest   {@code --manifest=} value or null
 * @param dest       {@code --dest=} value or null
 * @param cache      {@code --cache=} value or null
 * @param lang       {@code --lang=} value or null
 * @param log        {@code --log=} value or null
 * @param result     {@code --result=} value or null
 * @param uninstall  {@code --uninstall}: remove the installation in {@code --dest} from its record
 * @param purge      {@code --purge}: with {@code --uninstall}, also delete changed files and everything else below
 *                   {@code --dest}
 * @param noAssociations {@code --no-associations}: do not create the manifest's file associations
 * @param noPath     {@code --no-path}: do not add the manifest's PATH entries
 * @param repair     {@code --repair}: reinstall the recorded selection in {@code --dest}
 */
public record Arguments(boolean silent, boolean help, boolean version,
                        String manifest, String config, String dest, List<String> components, String lang, String log,
                        boolean acceptLicense, boolean dryRun, boolean quiet, boolean json, boolean allUsers,
                        Map<String, String> inputs,
                        boolean update, String cache, List<Long> waitPids, boolean relaunch, String result,
                        boolean uninstall, boolean purge, boolean noAssociations, boolean noPath, boolean repair) {

    /** Options that take no value. */
    static final Set<String> FLAGS = Set.of("silent", "help", "version", "accept-license", "dry-run", "quiet",
            "all-users", "update", "relaunch", "uninstall", "purge", "no-associations", "no-path", "repair");
    /** Options that take a value. */
    static final Set<String> VALUED = Set.of("manifest", "config", "dest", "components", "lang", "log", "output",
            "cache", "wait-pid", "result");
    /** Recognised, but their stories are not implemented yet: rejected with a clear message, not "unknown". */
    static final Set<String> NOT_YET = Set.of();

    public Arguments {
        components = List.copyOf(components);
        inputs = Map.copyOf(new LinkedHashMap<>(inputs));
        waitPids = List.copyOf(waitPids);
    }

    /** True if no window is wanted: silent run, help or version. */
    public boolean headless() {
        return silent || help || version;
    }

    /** @throws InvalidArgumentsException for anything the installer does not accept */
    public static Arguments parse(String... args) {
        boolean silent = false, help = false, version = false, acceptLicense = false, dryRun = false;
        boolean quiet = false, json = false, allUsers = false, update = false, relaunch = false;
        boolean uninstall = false, purge = false, noAssociations = false, noPath = false, repair = false;
        String manifest = null, config = null, dest = null, lang = null, log = null, cache = null, result = null;
        List<String> components = new ArrayList<>();
        Map<String, String> inputs = new LinkedHashMap<>();
        List<Long> waitPids = new ArrayList<>();

        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (!arg.startsWith("--")) {
                throw new InvalidArgumentsException(arg, "options start with --");
            }
            int eq = arg.indexOf('=');
            String name = (eq < 0 ? arg.substring(2) : arg.substring(2, eq)).toLowerCase(Locale.ROOT);
            String value = eq < 0 ? null : arg.substring(eq + 1);

            if (name.startsWith("input.")) {
                String id = name.substring("input.".length());
                if (id.isEmpty() || value == null) {
                    throw new InvalidArgumentsException(arg, "expected --input.<id>=<value>");
                }
                // The id keeps the caller's case: manifest ids are case-sensitive.
                inputs.put(arg.substring(2 + "input.".length(), eq), value);
                continue;
            }
            if (NOT_YET.contains(name)) {
                throw new InvalidArgumentsException("--" + name, "not available in this version");
            }
            if (FLAGS.contains(name)) {
                if (value != null) {
                    throw new InvalidArgumentsException(arg, "--" + name + " takes no value");
                }
            } else if (VALUED.contains(name)) {
                if (value == null || value.isEmpty()) {
                    throw new InvalidArgumentsException(arg, "expected --" + name + "=<value>");
                }
            } else {
                throw new InvalidArgumentsException(arg, "unknown option");
            }

            switch (name) {
                case "silent" -> silent = true;
                case "help" -> help = true;
                case "version" -> version = true;
                case "accept-license" -> acceptLicense = true;
                case "dry-run" -> dryRun = true;
                case "quiet" -> quiet = true;
                case "all-users" -> allUsers = true;
                case "update" -> update = true;
                case "relaunch" -> relaunch = true;
                case "uninstall" -> uninstall = true;
                case "purge" -> purge = true;
                case "no-associations" -> noAssociations = true;
                case "no-path" -> noPath = true;
                case "repair" -> repair = true;
                case "manifest" -> manifest = value;
                case "config" -> config = value;
                case "dest" -> dest = value;
                case "lang" -> lang = value;
                case "log" -> log = value;
                case "cache" -> cache = value;
                case "result" -> result = value;
                case "components" -> {
                    for (String id : value.split(",")) {
                        if (!id.isBlank()) {
                            components.add(id.strip());
                        }
                    }
                }
                case "output" -> {
                    switch (value.toLowerCase(Locale.ROOT)) {
                        case "json" -> json = true;
                        case "text" -> json = false;
                        default -> throw new InvalidArgumentsException(arg, "expected --output=text|json");
                    }
                }
                case "wait-pid" -> {
                    try {
                        long pid = Long.parseLong(value.strip());
                        if (pid <= 0) {
                            throw new NumberFormatException();
                        }
                        waitPids.add(pid);
                    } catch (NumberFormatException e) {
                        throw new InvalidArgumentsException(arg, "expected --wait-pid=<positive number>");
                    }
                }
                default -> throw new InvalidArgumentsException(arg, "unknown option");
            }
        }
        if (update && !silent) {
            throw new InvalidArgumentsException("--update", "only available together with --silent");
        }
        if (config != null && !silent) {
            throw new InvalidArgumentsException("--config", "only available together with --silent");
        }
        if (config != null && update) {
            // An update takes components and inputs from the installation's record, not from a file.
            throw new InvalidArgumentsException("--config", "cannot be combined with --update");
        }
        if (uninstall) {
            if (!silent) {
                throw new InvalidArgumentsException("--uninstall", "only available together with --silent");
            }
            // An uninstall takes everything from the installation's record; nothing of this would be used.
            Map<String, Boolean> unusable = new LinkedHashMap<>();
            unusable.put("--update", update);
            unusable.put("--config", config != null);
            unusable.put("--components", !components.isEmpty());
            unusable.put("--input.<id>", !inputs.isEmpty());
            unusable.put("--cache", cache != null);
            unusable.put("--relaunch", relaunch);
            unusable.put("--accept-license", acceptLicense);
            unusable.put("--all-users", allUsers);
            unusable.forEach((option, given) -> {
                if (given) {
                    throw new InvalidArgumentsException(option, "cannot be combined with --uninstall");
                }
            });
        } else if (purge) {
            throw new InvalidArgumentsException("--purge", "only available together with --uninstall");
        }
        if (repair) {
            if (!silent) {
                throw new InvalidArgumentsException("--repair", "only available together with --silent");
            }
            // A repair reinstalls the record's selection into the same folder; only its inputs stay editable.
            Map<String, Boolean> unusable = new LinkedHashMap<>();
            unusable.put("--update", update);
            unusable.put("--uninstall", uninstall);
            unusable.put("--config", config != null);
            unusable.put("--components", !components.isEmpty());
            unusable.put("--accept-license", acceptLicense);
            unusable.put("--all-users", allUsers);
            unusable.forEach((option, given) -> {
                if (given) {
                    throw new InvalidArgumentsException(option, "cannot be combined with --repair");
                }
            });
        }
        return new Arguments(silent, help, version, manifest, config, dest, components, lang, log, acceptLicense,
                dryRun, quiet, json, allUsers, inputs, update, cache, waitPids, relaunch, result, uninstall, purge,
                noAssociations, noPath, repair);
    }
}
