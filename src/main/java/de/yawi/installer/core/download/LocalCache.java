package de.yawi.installer.core.download;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.error.IntegrityException;
import de.yawi.installer.core.integrity.ChecksumVerifier;
import de.yawi.installer.core.manifest.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Artifacts someone else already fetched, offered to the installer with
 * {@code --cache=<dir>} the caller lists them in
 * {@code <dir>/SHA256SUMS} as {@code <sha256>  <relative path>}, one per
 * line. A source whose {@code sha256} appears there is taken from the cache
 * after the installer hashed the file itself; anything else is obtained the
 * usual way. The cache is read-only for the installer: nothing is written
 * there and cache files are never deleted after use.
 */
public final class LocalCache {

    private static final Logger LOG = LoggerFactory.getLogger(LocalCache.class);

    public static final String INDEX_FILE = "SHA256SUMS";

    private final Path dir;
    /** Lower-case hex checksum → path relative to {@link #dir} (as listed, with {@code /}). */
    private final Map<String, String> index;

    private LocalCache(Path dir, Map<String, String> index) {
        this.dir = dir;
        this.index = Map.copyOf(index);
    }

    /**
     * @throws IOException if the directory or its index is missing or unreadable
     *                     - an empty index is fine, it just never matches
     */
    public static LocalCache open(Path dir) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("cache directory " + root + " does not exist");
        }
        Path indexFile = root.resolve(INDEX_FILE);
        if (!Files.isRegularFile(indexFile)) {
            throw new IOException("cache index " + indexFile + " is missing");
        }
        return new LocalCache(root, parseIndex(Files.readString(indexFile, StandardCharsets.UTF_8)));
    }

    /** The {@code sha256sum} format: hex, two spaces (or space and asterisk), path; blank and {@code #} lines ignored. */
    static Map<String, String> parseIndex(String text) {
        Map<String, String> index = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space != 64) {
                LOG.warn("Ignoring cache index line without a sha256: {}", line);
                continue;
            }
            String hex = line.substring(0, 64).toLowerCase(Locale.ROOT);
            String path = line.substring(64).stripLeading();
            if (path.startsWith("*")) {
                path = path.substring(1);
            }
            if (path.isEmpty() || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                LOG.warn("Ignoring malformed cache index line: {}", line);
                continue;
            }
            index.putIfAbsent(hex, path);
        }
        return index;
    }

    public Path dir() {
        return dir;
    }

    public int size() {
        return index.size();
    }

    /**
     * The cached file for {@code source}, verified, or empty if the cache has
     * none (no checksum on the source, no index entry, file missing).
     *
     * @throws IntegrityException if the listed file exists but its content
     *                            does not hash to the source's checksum -
     *                            the contract wants that reported, not
     *                            silently replaced by a download
     */
    public Optional<Path> lookup(Source source, DownloadListener listener, CancellationToken cancellation)
            throws IOException {
        Objects.requireNonNull(source, "source");
        if (source.sha256() == null) {
            return Optional.empty();
        }
        String relative = index.get(source.sha256().toLowerCase(Locale.ROOT));
        if (relative == null) {
            LOG.debug("Source {}: not in cache {}", source.id(), dir);
            return Optional.empty();
        }
        Path file = dir.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!file.startsWith(dir)) {
            LOG.warn("Source {}: cache entry {} points outside the cache, ignored", source.id(), relative);
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) {
            LOG.info("Source {}: listed in cache as {} but the file is missing", source.id(), relative);
            return Optional.empty();
        }
        long total = Files.size(file);
        LOG.info("Source {}: verifying cached {} ({} bytes)", source.id(), file, total);
        listener.verifying(file.getFileName().toString(), total);
        long[] last = {0};
        ChecksumVerifier.verifyFile(file, source.sha256(), bytes -> {
            cancellation.checkpoint();
            if (bytes - last[0] >= 1L << 20) {
                last[0] = bytes;
                listener.progress(new DownloadProgress(bytes, total, 0, -1));
            }
        });
        listener.progress(new DownloadProgress(total, total, 0, -1));
        return Optional.of(file);
    }
}
