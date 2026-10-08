package de.yawi.installer.core.elevation;

import de.yawi.installer.core.manifest.ManifestOrigin;
import de.yawi.installer.core.xml.SecureXml;
import de.yawi.installer.core.xml.XmlText;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The hand-over plan for an elevated uninstall: what the elevated
 * child needs to remove an installation whose files sit in a place the calling
 * user cannot write. Unlike the install {@link ElevationPlan} it carries no
 * steps, artifacts or secrets - only the destination, the two {@code --purge}
 * flags, the manifest origin (the reverse commands come from the manifest, read
 * from {@code manifest.xml} next to this file) and the caller's environment, so
 * the child resolves {@code ${HOME}} & Co. like the parent would. Written to
 * {@code uninstall.xml} in the hand-over directory.
 */
public record UninstallPlan(Path destination, boolean deleteModified, boolean deleteUnrecorded,
                            ManifestOrigin origin, Map<String, String> env, Map<String, String> properties) {

    public static final int FORMAT_VERSION = 1;
    public static final String NAME = "uninstall.xml";

    public UninstallPlan {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(origin, "origin");
        env = Map.copyOf(env);
        properties = Map.copyOf(properties);
    }

    public String format() {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<uninstall version=\"").append(FORMAT_VERSION).append('"')
                .append(" destination=\"").append(esc(destination.toString())).append('"')
                .append(" deleteModified=\"").append(deleteModified).append('"')
                .append(" deleteUnrecorded=\"").append(deleteUnrecorded).append("\">\n");
        out.append("  <origin kind=\"").append(origin.kind().name())
                .append("\" location=\"").append(esc(origin.location()))
                .append("\" signature=\"").append(origin.signature().name()).append("\"/>\n");
        env.forEach((name, value) -> out.append("  <env name=\"").append(esc(name))
                .append("\" value=\"").append(esc(value)).append("\"/>\n"));
        properties.forEach((name, value) -> out.append("  <property name=\"").append(esc(name))
                .append("\" value=\"").append(esc(value)).append("\"/>\n"));
        out.append("</uninstall>\n");
        return out.toString();
    }

    public void write(Path file) throws IOException {
        Files.writeString(file, format(), StandardCharsets.UTF_8);
        HandoverDir.restrictFile(file);
    }

    public static UninstallPlan read(Path file) throws IOException {
        Document document;
        try {
            document = SecureXml.newDocumentBuilder().parse(file.toFile());
        } catch (SAXException e) {
            throw new IOException("uninstall plan " + file + " is not well-formed: " + e.getMessage(), e);
        }
        Element root = document.getDocumentElement();
        if (!"uninstall".equals(root.getTagName())) {
            throw new IOException("uninstall plan " + file + ": unexpected root <" + root.getTagName() + ">");
        }
        try {
            int version = Integer.parseInt(attr(root, "version"));
            if (version != FORMAT_VERSION) {
                throw new IOException("uninstall plan " + file + " has format version " + version + ", expected "
                        + FORMAT_VERSION);
            }
            Path destination = Path.of(attr(root, "destination"));
            boolean deleteModified = Boolean.parseBoolean(attr(root, "deleteModified"));
            boolean deleteUnrecorded = Boolean.parseBoolean(attr(root, "deleteUnrecorded"));
            List<Element> origins = PlanFile.children(root, "origin");
            if (origins.size() != 1) {
                throw new IOException("uninstall plan " + file + ": expected one <origin>, found " + origins.size());
            }
            Element o = origins.get(0);
            ManifestOrigin origin = new ManifestOrigin(ManifestOrigin.Kind.valueOf(attr(o, "kind")),
                    attr(o, "location"), ManifestOrigin.Signature.valueOf(attr(o, "signature")));
            Map<String, String> env = new LinkedHashMap<>();
            for (Element e : PlanFile.children(root, "env")) {
                env.put(attr(e, "name"), attr(e, "value"));
            }
            Map<String, String> properties = new LinkedHashMap<>();
            for (Element e : PlanFile.children(root, "property")) {
                properties.put(attr(e, "name"), attr(e, "value"));
            }
            return new UninstallPlan(destination, deleteModified, deleteUnrecorded, origin, env, properties);
        } catch (IllegalArgumentException e) {
            throw new IOException("uninstall plan " + file + " is incomplete: " + e.getMessage(), e);
        }
    }

    private static String attr(Element e, String name) {
        if (!e.hasAttribute(name)) {
            throw new IllegalArgumentException("<" + e.getTagName() + "> without " + name);
        }
        return e.getAttribute(name);
    }

    private static String esc(String text) {
        return XmlText.escape(text);
    }
}
