package de.yawi.installer.core.integrity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * How strictly a manifest from the network must be signed.
 *
 * <p>Fixed when the installer is built: Maven filters
 * {@code yawi.signature.policy} into {@value #RESOURCE}. The default is
 * {@link #STRICT}; a developer builds with
 * {@code -Dyawi.signature.policy=lenient} to run against an unsigned manifest
 * URL. E16 refuses to package a release with {@link #LENIENT}.
 */
public enum SignaturePolicy {

    /** A manifest from the network without a valid signature is rejected. */
    STRICT,
    /** An unsigned network manifest loads, but its command steps are still refused (E10-S03-T03). */
    LENIENT;

    public static final String RESOURCE = "/de/yawi/installer/integrity/signature.properties";
    static final String PROPERTY = "policy";

    private static final Logger LOG = LoggerFactory.getLogger(SignaturePolicy.class);

    /** The policy baked into this build; anything unreadable or unknown is {@link #STRICT}. */
    public static SignaturePolicy embedded() {
        try (InputStream in = SignaturePolicy.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOG.debug("No {} in the build, signature policy is strict", RESOURCE);
                return STRICT;
            }
            Properties properties = new Properties();
            properties.load(in);
            return parse(properties.getProperty(PROPERTY));
        } catch (IOException e) {
            LOG.warn("Cannot read {}, signature policy is strict: {}", RESOURCE, e.toString());
            return STRICT;
        }
    }

    static SignaturePolicy parse(String value) {
        if (value == null) {
            return STRICT;
        }
        String trimmed = value.trim();
        // An unfiltered placeholder means the resource was copied without Maven filtering.
        if (trimmed.isEmpty() || trimmed.startsWith("${")) {
            return STRICT;
        }
        try {
            SignaturePolicy policy = valueOf(trimmed.toUpperCase(Locale.ROOT));
            if (policy == LENIENT) {
                LOG.warn("Signature policy is LENIENT: unsigned manifests from the network are accepted. "
                        + "This is a development build.");
            }
            return policy;
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown signature policy '{}', using strict", trimmed);
            return STRICT;
        }
    }
}
