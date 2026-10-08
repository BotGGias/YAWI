package de.yawi.installer.core.state;

import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.xml.SecureXml;
import de.yawi.installer.core.xml.XmlText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The per-user register of a product's installations: one small
 * XML file per destination under {@code <configDir(productId)>/installer/installations/},
 * so an installation stays known after its folder was deleted. The record
 * inside the destination remains the source of truth; the register only
 * points at it.
 *
 * <p>The directory is {@code configDir(productId)/installer}, never
 * {@code configDir(productId)} itself: for {@code your-product} that is the
 * data directory of su-node and off limits (launcher update contract).
 *
 * <p>Files are keyed by the destination, so several installations of one
 * product in different folders coexist and a re-install or update of the same
 * folder replaces its entry. Reading never throws; an unreadable file is
 * skipped with a warning, so one damaged entry cannot block the wizard.
 */
public final class InstallationRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(InstallationRegistry.class);

    public static final int FORMAT_VERSION = 1;
    /** Below {@link Platform#configDir(String)}. */
    public static final String DIRECTORY = "installer";
    public static final String INSTALLATIONS = "installations";
    private static final String SUFFIX = ".xml";
    private static final String TEMP_SUFFIX = ".tmp";

    /** One register entry; {@code file} is where it lives, for {@link #unregister}. */
    public record Registration(String productId, String productVersion, Path destination, Instant registeredAt,
                               Path file) {
        public Registration {
            Objects.requireNonNull(productId, "productId");
            Objects.requireNonNull(productVersion, "productVersion");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(registeredAt, "registeredAt");
        }
    }

    private final Path directory;
    private final String productId;

    private InstallationRegistry(Path directory, String productId) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.productId = Objects.requireNonNull(productId, "productId");
    }

    /** The register of {@code productId} on this platform. */
    public static InstallationRegistry forPlatform(Platform platform, String productId) {
        return new InstallationRegistry(platform.configDir(productId).resolve(DIRECTORY), productId);
    }

    /** A register at an explicit directory (tests). */
    public static InstallationRegistry at(Path directory, String productId) {
        return new InstallationRegistry(directory, productId);
    }

    public Path directory() {
        return directory;
    }

    public String productId() {
        return productId;
    }

    /**
     * The file name stem for a destination: 16 hex characters of the SHA-256
     * of its normalised absolute path. On Windows the path is lower-cased
     * first, because {@code Path.equals} ignores case there while
     * {@code toString} does not.
     */
    public static String key(Path destination) {
        String text = destination.toAbsolutePath().normalize().toString();
        if (File.separatorChar == '\\') {
            text = text.toLowerCase(Locale.ROOT);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Where the entry for {@code destination} lives (whether or not it exists). */
    public Path fileFor(Path destination) {
        return directory.resolve(INSTALLATIONS).resolve(key(destination) + SUFFIX);
    }

    /**
     * Writes (or replaces) the entry for the record's destination. The file
     * is written next to its final name and moved into place, so a reader
     * never sees a half-written entry.
     */
    public void register(InstallationRecord record) throws IOException {
        Path file = fileFor(record.destination());
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        String text = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<installation version=\"" + FORMAT_VERSION + "\""
                + " product=\"" + XmlText.escape(record.productId()) + "\""
                + " productVersion=\"" + XmlText.escape(record.productVersion()) + "\""
                + " destination=\"" + XmlText.escape(record.destination().toString()) + "\""
                + " registeredAt=\"" + Instant.now() + "\"/>\n";
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes the entry's file; false if it was already gone. */
    public boolean unregister(Registration registration) throws IOException {
        return Files.deleteIfExists(registration.file());
    }

    /** Removes the entry of a destination (E14-S03, after an uninstall); false if there was none. */
    public boolean unregister(Path destination) throws IOException {
        return Files.deleteIfExists(fileFor(destination));
    }

    /**
     * Every readable entry of this product, newest first. A missing directory
     * is an empty register; files that are no entries (temp files, damaged
     * XML, another product) are skipped with a warning.
     */
    public List<Registration> list() {
        Path dir = directory.resolve(INSTALLATIONS);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Registration> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(SUFFIX)).sorted().forEach(f -> read(f).ifPresent(found::add));
        } catch (IOException e) {
            LOG.warn("Cannot list the installation register {}: {}", dir, e.toString());
            return List.of();
        }
        found.sort(Comparator.comparing(Registration::registeredAt).reversed());
        return List.copyOf(found);
    }

    /** {@link #list()} with each entry checked against its destination. */
    public List<ExistingInstallation> installations() {
        return list().stream().map(r -> ExistingInstallation.inspect(r, productId)).toList();
    }

    private Optional<Registration> read(Path file) {
        try {
            Element root = SecureXml.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            if (!root.getTagName().equals("installation")) {
                throw new IOException("root is <" + root.getTagName() + ">, expected <installation>");
            }
            int version = Integer.parseInt(root.getAttribute("version"));
            if (version != FORMAT_VERSION) {
                throw new IOException("format version " + version + ", supported: " + FORMAT_VERSION);
            }
            String product = root.getAttribute("product");
            if (!product.equals(productId)) {
                throw new IOException("entry is for product '" + product + "', this register is for '" + productId + "'");
            }
            return Optional.of(new Registration(product, root.getAttribute("productVersion"),
                    Path.of(root.getAttribute("destination")), Instant.parse(root.getAttribute("registeredAt")), file));
        } catch (IOException | SAXException | RuntimeException e) {
            LOG.warn("Skipping unreadable register entry {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
