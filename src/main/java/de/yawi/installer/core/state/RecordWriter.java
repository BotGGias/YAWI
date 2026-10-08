package de.yawi.installer.core.state;

import de.yawi.installer.core.state.InstallationRecord.CreatedDirectory;
import de.yawi.installer.core.state.InstallationRecord.CreatedFile;
import de.yawi.installer.core.state.InstallationRecord.Entry;
import de.yawi.installer.core.state.InstallationRecord.ModeChanged;
import de.yawi.installer.core.state.InstallationRecord.ReplacedFile;
import de.yawi.installer.core.state.InstallationRecord.RunFinished;
import de.yawi.installer.core.state.InstallationRecord.StepFinished;
import de.yawi.installer.core.state.InstallationRecord.StepStarted;
import de.yawi.installer.core.xml.XmlText;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Writes an {@link InstallationRecord} as it grows: the header once, then one
 * line per entry, flushed immediately; {@link #close()} adds the closing tag.
 * A crash leaves a file without {@code </record>}, which {@link RecordReader}
 * tolerates - that is the point of appending instead of rewriting.
 *
 * <p>Default location: {@code <destination>/.installer/record.xml}. The
 * directory and the file are themselves part of the installation and are
 * recorded first, so uninstall removes them last.
 */
public final class RecordWriter implements AutoCloseable {

    public static final String DIRECTORY = ".installer";
    public static final String FILE_NAME = "record.xml";

    private final Path file;
    private final BufferedWriter out;
    private boolean closed;

    private RecordWriter(Path file, BufferedWriter out) {
        this.file = file;
        this.out = out;
    }

    /** The default record file of a destination. */
    public static Path defaultFile(Path destination) {
        return destination.resolve(DIRECTORY).resolve(FILE_NAME);
    }

    /**
     * Creates {@code file} (and its directory), writes the header and hooks
     * itself into the record so every further entry lands on disk. The
     * directory and the file are recorded as created if they were.
     */
    public static RecordWriter open(Path file, InstallationRecord record) throws IOException {
        Path dir = file.getParent();
        boolean dirCreated = dir != null && !Files.isDirectory(dir);
        if (dirCreated) {
            Files.createDirectories(dir);
        }
        boolean fileCreated = !Files.exists(file);
        BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        RecordWriter writer = new RecordWriter(file, out);
        writer.writeHeader(record);
        if (dirCreated) {
            record.directoryCreated(dir);
        }
        if (fileCreated) {
            record.fileCreated(file);
        }
        // Entries already in the record (none normally) are written too.
        record.entries().forEach(writer::append);
        record.onEntry(writer::append);
        return writer;
    }

    public Path file() {
        return file;
    }

    private void writeHeader(InstallationRecord record) throws IOException {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.write("<record version=\"" + InstallationRecord.FORMAT_VERSION + "\""
                + " product=\"" + escape(record.productId()) + "\""
                + " productVersion=\"" + escape(record.productVersion()) + "\""
                + " started=\"" + record.startedAt() + "\""
                + " destination=\"" + escape(record.destination().toString()) + "\">\n");
        for (String component : record.components()) {
            out.write("  <component id=\"" + escape(component) + "\"/>\n");
        }
        for (Map.Entry<String, String> input : record.inputs().entrySet()) {
            out.write("  <input id=\"" + escape(input.getKey()) + "\" value=\"" + escape(input.getValue()) + "\"/>\n");
        }
        out.flush();
    }

    /** Appends one line and flushes, so the entry survives whatever happens next. */
    public synchronized void append(Entry entry) {
        if (closed) {
            return;
        }
        String line = switch (entry) {
            case CreatedFile f -> "  <file path=\"" + escape(f.path()) + "\"/>\n";
            case CreatedDirectory d -> "  <dir path=\"" + escape(d.path()) + "\"/>\n";
            case ReplacedFile r -> "  <replaced path=\"" + escape(r.path()) + "\" backup=\"" + escape(r.backup()) + "\"/>\n";
            case ModeChanged m -> "  <mode path=\"" + escape(m.path()) + "\" mode=\"" + escape(m.mode()) + "\"/>\n";
            case StepStarted s -> "  <step id=\"" + escape(s.stepId()) + "\" event=\"started\"/>\n";
            case StepFinished s -> "  <step id=\"" + escape(s.stepId()) + "\" event=\"finished\" outcome=\""
                    + escape(s.outcome()) + "\""
                    + (s.message() == null ? "" : " message=\"" + escape(s.message()) + "\"") + "/>\n";
            case RunFinished f -> "  <finished at=\"" + f.at() + "\"/>\n";
        };
        try {
            out.write(line);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to " + file, e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try (out) {
            out.write("</record>\n");
        }
    }

    /** XML attribute escaping, shared with the other hand-written XML files. */
    static String escape(String text) {
        return XmlText.escape(text);
    }
}
