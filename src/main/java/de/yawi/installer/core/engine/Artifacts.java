package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.Source;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where the data of a {@code <source>} comes from at execution time.
 *
 * <p>Until the only implementation is {@link BundledArtifacts};
 * provider chain (bundled, HTTP, torrent with fallback) replaces the
 * implementation, not this interface. The engine never cares whether the
 * bytes were shipped or downloaded.
 */
public interface Artifacts {

    /**
     * Opens the source's data for reading, once.
     *
     * @throws de.yawi.installer.core.error.SourceUnavailableException if no
     *         provider can deliver it
     * @throws IOException if the provider exists but cannot be read
     */
    InputStream open(Source source) throws IOException;

    /** A name for the data, for the log and to guess the archive format: the bundled path or the URL's file name. */
    String describe(Source source);
}
