package de.yawi.installer.cli;

import de.yawi.installer.core.download.DownloadProgress;
import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.download.Formats;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.Rollback;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.engine.StepOutcome;
import de.yawi.installer.core.i18n.ManifestMessages;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.manifest.ByteSize;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.Source;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The silent mode's progress on stdout: plain lines that a log
 * or a pipe can hold, no cursor tricks. Text mode speaks the user's
 * language; {@code --output=json} prints one JSON object per event for
 * tooling; {@code --quiet} prints nothing here (errors go to stderr).
 * Download progress is throttled to one line every {@link #THROTTLE}.
 */
public final class ConsoleReporter implements ProgressListener {

    public enum Mode { TEXT, JSON, QUIET }

    static final Duration THROTTLE = Duration.ofSeconds(2);

    private final PrintStream out;
    private final Mode mode;
    private final Messages messages;
    private final ManifestMessages manifestMessages;
    private final Locale locale;
    private Instant lastDownloadLine = Instant.EPOCH;

    public ConsoleReporter(PrintStream out, Mode mode, Messages messages, ManifestMessages manifestMessages,
                           Locale locale) {
        this.out = out;
        this.mode = mode;
        this.messages = messages;
        this.manifestMessages = manifestMessages;
        this.locale = locale;
    }

    public Mode mode() {
        return mode;
    }

    /** A free line outside the listener's events (start, result, dry run). */
    public void line(String key, Object... args) {
        if (mode == Mode.TEXT) {
            out.println(messages.get(locale, key, args));
        } else if (mode == Mode.JSON) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("key", key);
            fields.put("text", messages.get(locale, key, args));
            event("message", fields);
        }
    }

    /** Raw text, e.g. a dry-run line; only in text mode. */
    public void raw(String text) {
        if (mode == Mode.TEXT) {
            out.println(text);
        } else if (mode == Mode.JSON) {
            event("text", ordered("text", text));
        }
    }

    @Override
    public void downloadStarted(Source source, int index, int count) {
        if (mode == Mode.TEXT) {
            out.println(messages.get(locale, "progress.download", source.id(), index + 1, count));
        } else if (mode == Mode.JSON) {
            event("downloadStarted", ordered("source", source.id(), "index", index, "count", count));
        }
        lastDownloadLine = Instant.EPOCH;
    }

    @Override
    public void downloadVerifying(Source source) {
        if (mode == Mode.TEXT) {
            out.println(messages.get(locale, "progress.verify", source.id()));
        } else if (mode == Mode.JSON) {
            event("verifying", ordered("source", source.id()));
        }
    }

    @Override
    public void downloadProgress(Source source, DownloadProgress progress) {
        Instant now = Instant.now();
        boolean complete = progress.total() > 0 && progress.bytes() >= progress.total();
        if (!complete && Duration.between(lastDownloadLine, now).compareTo(THROTTLE) < 0) {
            return;
        }
        lastDownloadLine = now;
        if (mode == Mode.TEXT) {
            String total = progress.total() > 0 ? ByteSize.format(progress.total(), locale) : "?";
            out.println("  " + messages.get(locale, "progress.download.status",
                    ByteSize.format(progress.bytes(), locale), total,
                    Formats.speed(progress.bytesPerSecond(), locale),
                    progress.etaSeconds() >= 0 ? Formats.duration(progress.etaSeconds()) : "?"));
        } else if (mode == Mode.JSON) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("source", source.id());
            fields.put("bytes", progress.bytes());
            fields.put("total", progress.total());
            fields.put("bytesPerSecond", Math.round(progress.bytesPerSecond()));
            event("downloadProgress", fields);
        }
    }

    @Override
    public void downloadFallback(Source source, ProviderKind from, ProviderKind to, String reason) {
        if (mode == Mode.TEXT) {
            out.println(messages.get(locale, "progress.fallback", source.id(), kindName(from), kindName(to), reason));
        } else if (mode == Mode.JSON) {
            event("fallback", ordered("source", source.id(), "from", from.name().toLowerCase(java.util.Locale.ROOT),
                    "to", to.name().toLowerCase(java.util.Locale.ROOT), "reason", reason));
        }
    }

    private String kindName(ProviderKind kind) {
        return messages.get(locale, "summary.source." + kind.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public void downloadFinished(Source source) {
        if (mode == Mode.JSON) {
            event("downloadFinished", ordered("source", source.id()));
        }
    }

    @Override
    public void stepStarted(InstallStep step, int index, int count) {
        if (mode == Mode.TEXT) {
            String name;
            if (step instanceof InstallStep.Shortcut s) {
                name = messages.get(locale, "progress.shortcut", s.shortcut().name());
            } else if (step instanceof InstallStep.FileAssociation a) {
                name = messages.get(locale, "progress.association", a.association().extension());
            } else if (step instanceof InstallStep.PathEntries) {
                name = messages.get(locale, "progress.pathEntry");
            } else {
                name = manifestMessages.stepName(locale, step.id());
            }
            out.println(messages.get(locale, "progress.step", index + 1, count, name));
        } else if (mode == Mode.JSON) {
            event("stepStarted", ordered("step", step.id(), "index", index, "count", count));
        }
    }

    @Override
    public void stepProgress(double fraction, String message) {
        if (mode == Mode.JSON) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("fraction", fraction);
            if (message != null) {
                fields.put("message", message);
            }
            event("stepProgress", fields);
        }
    }

    @Override
    public void stepFinished(InstallStep step, StepOutcome outcome) {
        if (mode == Mode.JSON) {
            event("stepFinished", ordered("step", step.id(), "outcome", outcome.name().toLowerCase(Locale.ROOT)));
        } else if (mode == Mode.TEXT && outcome != StepOutcome.DONE) {
            out.println("  -> " + outcome.name().toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public void overall(double fraction) {
        if (mode == Mode.JSON) {
            event("overall", ordered("fraction", fraction));
        }
    }

    @Override
    public void output(String line) {
        if (mode == Mode.TEXT) {
            out.println("  | " + line);
        } else if (mode == Mode.JSON) {
            event("output", ordered("line", line));
        }
    }

    // --- rollback -------------------------------------------------

    @Override
    public void rollbackStarted(int total) {
        if (mode == Mode.JSON) {
            event("rollbackStarted", ordered("total", total));
        } else {
            line("cli.rollback.start");
        }
    }

    @Override
    public void rollbackProgress(int done, int total, String what) {
        if (mode == Mode.JSON) {
            Map<String, Object> fields = ordered("done", done, "total", total);
            if (what != null) {
                fields.put("what", what);
            }
            event("rollbackProgress", fields);
        }
    }

    @Override
    public void rollbackFinished(Rollback.Report report) {
        if (mode == Mode.JSON) {
            event("rollbackFinished", ordered("clean", report.clean(), "restored", report.restored(),
                    "deleted", report.deleted(), "failures", report.failures().size(),
                    "stepsWithoutReverse", String.join(",", report.stepsWithoutReverse())));
        } else {
            line(report.clean() ? "cli.rollback.done" : "cli.rollback.failed", report.restored(), report.deleted(),
                    report.failures().size(), LogSetup.currentLogFile());
            if (!report.stepsWithoutReverse().isEmpty()) {
                line("cli.rollback.noReverse", String.join(", ", report.stepsWithoutReverse()));
            }
        }
    }

    // --- uninstall ------------------------------------------------

    @Override
    public void uninstallStarted(int total) {
        if (mode == Mode.JSON) {
            event("uninstallStarted", ordered("total", total));
        } else {
            line("cli.uninstall.start", total);
        }
    }

    @Override
    public void uninstallProgress(int done, int total, String what) {
        if (mode == Mode.JSON) {
            Map<String, Object> fields = ordered("done", done, "total", total);
            if (what != null) {
                fields.put("what", what);
            }
            event("uninstallProgress", fields);
        }
    }

    @Override
    public void uninstallFinished(Uninstaller.Report report) {
        if (mode == Mode.JSON) {
            event("uninstallFinished", ordered("clean", report.clean(), "cancelled", report.cancelled(),
                    "deleted", report.deleted(), "stepsReversed", report.stepsReversed(),
                    "keptModified", String.join(",", report.keptModified()),
                    "keptUserData", String.join(",", report.keptUnrecorded()),
                    "failures", report.failures().size(),
                    "stepsWithoutReverse", String.join(",", report.stepsWithoutReverse()),
                    "destinationRemoved", report.destinationRemoved()));
            return;
        }
        report.keptModified().forEach(p -> line("cli.uninstall.kept.modified", p));
        report.keptUnrecorded().forEach(p -> line("cli.uninstall.kept.userData", p));
        if (!report.stepsWithoutReverse().isEmpty()) {
            line("cli.uninstall.noReverse", String.join(", ", report.stepsWithoutReverse()));
        }
        if (report.cancelled()) {
            line("cli.uninstall.cancelled");
        } else if (!report.clean()) {
            line("cli.uninstall.failed", report.deleted(), report.failures().size(), LogSetup.currentLogFile());
        }
    }

    /** Fields in the given order - Map.of would shuffle them and the lines should read the same every time. */
    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return fields;
    }

    private void event(String name, Map<String, Object> fields) {
        StringBuilder json = new StringBuilder("{\"event\":\"").append(ResultFile.escape(name)).append('"');
        fields.forEach((k, v) -> {
            json.append(",\"").append(ResultFile.escape(k)).append("\":");
            if (v instanceof Number || v instanceof Boolean) {
                json.append(v);
            } else {
                json.append('"').append(ResultFile.escape(String.valueOf(v))).append('"');
            }
        });
        out.println(json.append('}'));
    }
}
