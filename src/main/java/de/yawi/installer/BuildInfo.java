package de.yawi.installer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The installer's own build coordinates, read once from
 * {@value #RESOURCE}, which Maven fills by resource filtering. These are the
 * <em>installer's</em> version, commit and build time - not the version of the
 * product being installed, which comes from the manifest
 * ({@code product/@version}) and stays the one shown in {@code --version} and
 * the window title. The build coordinates are additional: a second
 * {@code --version} line and a startup log line, so a bug report names the
 * exact build.
 */
public final class BuildInfo {

    static final String RESOURCE = "/de/yawi/installer/build.properties";

    private static final Logger LOG = LoggerFactory.getLogger(BuildInfo.class);

    private static final BuildInfo EMBEDDED = load();

    private final String version;
    private final String commit;
    private final String time;

    BuildInfo(String version, String commit, String time) {
        this.version = version;
        this.commit = commit;
        this.time = time;
    }

    /** The build baked into this jar; unknown fields are empty, never null. */
    public static BuildInfo embedded() {
        return EMBEDDED;
    }

    /** The installer version, e.g. {@code 1.0.0-SNAPSHOT}; {@code "unknown"} if the resource is missing. */
    public String version() {
        return version;
    }

    /** The abbreviated git commit, or empty outside a checkout. */
    public String commit() {
        return commit;
    }

    /** The build time (ISO), or empty. */
    public String time() {
        return time;
    }

    /** One line for {@code --version} and logs, e.g. {@code 1.0.0-SNAPSHOT (a1b2c3d, 2026-09-14T…)}. */
    public String summary() {
        StringBuilder sb = new StringBuilder(version);
        if (!commit.isEmpty() || !time.isEmpty()) {
            sb.append(" (");
            sb.append(commit);
            if (!commit.isEmpty() && !time.isEmpty()) {
                sb.append(", ");
            }
            sb.append(time);
            sb.append(')');
        }
        return sb.toString();
    }

    private static BuildInfo load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.debug("No {} in the build; build info is unknown", RESOURCE);
                return new BuildInfo("unknown", "", "");
            }
            Properties p = new Properties();
            p.load(in);
            return new BuildInfo(clean(p.getProperty("app.version"), "unknown"),
                    clean(p.getProperty("build.commit"), ""), clean(p.getProperty("build.time"), ""));
        } catch (IOException e) {
            LOG.warn("Cannot read {}; build info is unknown: {}", RESOURCE, e.toString());
            return new BuildInfo("unknown", "", "");
        }
    }

    /** An unfiltered placeholder ({@code ${...}}) means the resource was copied without Maven filtering. */
    private static String clean(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("${")) {
            return fallback;
        }
        return trimmed;
    }
}
