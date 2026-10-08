package de.yawi.installer.core.elevation;

import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.xml.SecureXml;
import de.yawi.installer.core.xml.XmlText;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code result.xml} of an elevated uninstall: either the
 * {@link Uninstaller.Report} the child produced, or a {@link ElevatedResult.Failure}
 * if it never got that far (plan or record unreadable). The report travels whole
 * so the parent shows what was removed and what was deliberately kept, in the
 * user's language for a failure. The register entry is not touched here - the
 * unelevated parent removes it (the child runs as root and must not write the
 * caller's profile).
 */
public record UninstallResult(Optional<ElevatedResult.Failure> failure, Optional<Uninstaller.Report> report) {

    public static final int FORMAT_VERSION = 1;

    public static UninstallResult ofReport(Uninstaller.Report report) {
        return new UninstallResult(Optional.empty(), Optional.of(report));
    }

    public static UninstallResult failed(InstallerException e) {
        return new UninstallResult(Optional.of(ElevatedResult.Failure.of(e)), Optional.empty());
    }

    public boolean succeeded() {
        return failure.isEmpty() && report.map(Uninstaller.Report::clean).orElse(false);
    }

    public int exitCode() {
        return failure.map(f -> f.code().exitCode())
                .orElseGet(() -> report.map(r -> r.cancelled() ? ErrorCode.CANCELLED.exitCode()
                        : r.failures().isEmpty() ? 0 : ErrorCode.GENERAL.exitCode()).orElse(ErrorCode.GENERAL.exitCode()));
    }

    public String format() {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<uninstallResult version=\"").append(FORMAT_VERSION).append("\" exitCode=\"")
                .append(exitCode()).append("\">\n");
        failure.ifPresent(f -> {
            out.append("  <failure code=\"").append(f.code().name()).append("\" messageKey=\"").append(esc(f.messageKey()))
                    .append("\" technical=\"").append(esc(f.technical())).append("\">\n");
            f.args().forEach(a -> out.append("    <arg>").append(esc(a)).append("</arg>\n"));
            out.append("  </failure>\n");
        });
        report.ifPresent(r -> {
            out.append("  <report deleted=\"").append(r.deleted()).append("\" stepsReversed=\"").append(r.stepsReversed())
                    .append("\" cancelled=\"").append(r.cancelled()).append("\" destinationRemoved=\"")
                    .append(r.destinationRemoved()).append("\">\n");
            r.keptModified().forEach(p -> out.append("    <keptModified>").append(esc(p)).append("</keptModified>\n"));
            r.keptUnrecorded().forEach(p -> out.append("    <keptUnrecorded>").append(esc(p)).append("</keptUnrecorded>\n"));
            r.stepsWithoutReverse().forEach(s ->
                    out.append("    <stepWithoutReverse>").append(esc(s)).append("</stepWithoutReverse>\n"));
            r.failures().forEach(s -> out.append("    <failure>").append(esc(s)).append("</failure>\n"));
            out.append("  </report>\n");
        });
        out.append("</uninstallResult>\n");
        return out.toString();
    }

    public void write(Path file) throws IOException {
        Files.writeString(file, format(), StandardCharsets.UTF_8);
    }

    /** @return empty if the file is empty (the child never wrote it) */
    public static Optional<UninstallResult> read(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return Optional.empty();
        }
        Document document;
        try {
            document = SecureXml.newDocumentBuilder().parse(file.toFile());
        } catch (SAXException e) {
            throw new IOException("uninstall result " + file + " is not well-formed: " + e.getMessage(), e);
        }
        Element root = document.getDocumentElement();
        try {
            Optional<ElevatedResult.Failure> failure = Optional.empty();
            List<Element> failures = PlanFile.children(root, "failure");
            if (!failures.isEmpty()) {
                Element f = failures.get(0);
                List<String> args = new ArrayList<>();
                PlanFile.children(f, "arg").forEach(a -> args.add(a.getTextContent()));
                failure = Optional.of(new ElevatedResult.Failure(ErrorCode.valueOf(f.getAttribute("code")),
                        f.getAttribute("messageKey"), args, f.getAttribute("technical"), Optional.empty(),
                        Optional.empty(), 0));
            }
            Optional<Uninstaller.Report> report = Optional.empty();
            List<Element> reports = PlanFile.children(root, "report");
            if (!reports.isEmpty()) {
                Element r = reports.get(0);
                report = Optional.of(new Uninstaller.Report(
                        Integer.parseInt(r.getAttribute("deleted")), Integer.parseInt(r.getAttribute("stepsReversed")),
                        texts(r, "keptModified"), texts(r, "keptUnrecorded"), texts(r, "stepWithoutReverse"),
                        texts(r, "failure"), Boolean.parseBoolean(r.getAttribute("cancelled")),
                        Boolean.parseBoolean(r.getAttribute("destinationRemoved"))));
            }
            return Optional.of(new UninstallResult(failure, report));
        } catch (IllegalArgumentException e) {
            throw new IOException("uninstall result " + file + " is incomplete: " + e.getMessage(), e);
        }
    }

    private static List<String> texts(Element parent, String name) {
        List<String> result = new ArrayList<>();
        PlanFile.children(parent, name).forEach(e -> result.add(e.getTextContent()));
        return result;
    }

    private static String esc(String text) {
        return XmlText.escape(text);
    }
}
