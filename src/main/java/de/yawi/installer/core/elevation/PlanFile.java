package de.yawi.installer.core.elevation;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.manifest.ManifestOrigin;
import de.yawi.installer.core.xml.SecureXml;
import de.yawi.installer.core.xml.XmlText;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code plan.xml} in the hand-over directory: hand-written on the way out
 * (like the record), parsed with the hardened DOM parser on the way in. The
 * file carries input secrets, so {@link ElevatedRun} creates it with owner-only
 * rights and the child deletes it as soon as it is read.
 */
public final class PlanFile {

    public static final String NAME = "plan.xml";

    private PlanFile() {
    }

    public static String format(ElevationPlan plan) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<elevation version=\"").append(ElevationPlan.FORMAT_VERSION).append('"')
                .append(" mode=\"").append(plan.mode().name()).append('"')
                .append(" destination=\"").append(esc(plan.destination().toString())).append('"')
                .append(" startedAt=\"").append(plan.startedAt()).append('"')
                .append(" chownDestination=\"").append(plan.chownDestination()).append('"');
        plan.ownerUser().ifPresent(u -> out.append(" ownerUser=\"").append(esc(u)).append('"'));
        plan.ownerGroup().ifPresent(g -> out.append(" ownerGroup=\"").append(esc(g)).append('"'));
        out.append(">\n");
        out.append("  <origin kind=\"").append(plan.origin().kind().name())
                .append("\" location=\"").append(esc(plan.origin().location()))
                .append("\" signature=\"").append(plan.origin().signature().name()).append("\"/>\n");
        plan.selected().stream().sorted().forEach(id -> out.append("  <component id=\"").append(esc(id)).append("\"/>\n"));
        plan.inputs().forEach((id, value) -> out.append("  <input id=\"").append(esc(id))
                .append("\" value=\"").append(esc(value)).append("\"/>\n"));
        plan.stepIds().forEach(id -> out.append("  <step id=\"").append(esc(id)).append("\"/>\n"));
        plan.artifacts().forEach((id, a) -> {
            out.append("  <artifact source=\"").append(esc(id)).append("\" kind=\"")
                    .append(a.kind().name().toLowerCase(Locale.ROOT)).append('"');
            a.file().ifPresent(f -> out.append(" file=\"").append(esc(f.toString())).append('"'));
            a.classpathRef().ifPresent(r -> out.append(" ref=\"").append(esc(r)).append('"'));
            out.append("/>\n");
        });
        plan.env().forEach((name, value) -> out.append("  <env name=\"").append(esc(name))
                .append("\" value=\"").append(esc(value)).append("\"/>\n"));
        plan.properties().forEach((name, value) -> out.append("  <property name=\"").append(esc(name))
                .append("\" value=\"").append(esc(value)).append("\"/>\n"));
        out.append("</elevation>\n");
        return out.toString();
    }

    /** Writes the plan; the caller has created {@code file}'s directory with restricted rights. */
    public static void write(ElevationPlan plan, Path file) throws IOException {
        Files.writeString(file, format(plan), StandardCharsets.UTF_8);
        HandoverDir.restrictFile(file);
    }

    /**
     * @throws IOException if the file is missing, not well-formed, of another version, or incomplete
     */
    public static ElevationPlan read(Path file) throws IOException {
        Document document;
        try {
            document = SecureXml.newDocumentBuilder().parse(file.toFile());
        } catch (SAXException e) {
            throw new IOException("plan file " + file + " is not well-formed: " + e.getMessage(), e);
        }
        Element root = document.getDocumentElement();
        if (!"elevation".equals(root.getTagName())) {
            throw new IOException("plan file " + file + ": unexpected root <" + root.getTagName() + ">");
        }
        int version = Integer.parseInt(attr(root, "version"));
        if (version != ElevationPlan.FORMAT_VERSION) {
            throw new IOException("plan file " + file + " has format version " + version + ", expected "
                    + ElevationPlan.FORMAT_VERSION);
        }
        try {
            ElevationNeed.Mode mode = ElevationNeed.Mode.valueOf(attr(root, "mode"));
            Path destination = Path.of(attr(root, "destination"));
            Instant startedAt = Instant.parse(attr(root, "startedAt"));
            boolean chown = Boolean.parseBoolean(attr(root, "chownDestination"));
            Optional<String> ownerUser = optional(root, "ownerUser");
            Optional<String> ownerGroup = optional(root, "ownerGroup");

            Element originElement = single(root, "origin", file);
            ManifestOrigin origin = new ManifestOrigin(ManifestOrigin.Kind.valueOf(attr(originElement, "kind")),
                    attr(originElement, "location"),
                    ManifestOrigin.Signature.valueOf(attr(originElement, "signature")));

            Set<String> selected = new LinkedHashSet<>();
            for (Element e : children(root, "component")) {
                selected.add(attr(e, "id"));
            }
            Map<String, String> inputs = new LinkedHashMap<>();
            for (Element e : children(root, "input")) {
                inputs.put(attr(e, "id"), attr(e, "value"));
            }
            List<String> steps = new ArrayList<>();
            for (Element e : children(root, "step")) {
                steps.add(attr(e, "id"));
            }
            Map<String, ElevationPlan.Artifact> artifacts = new LinkedHashMap<>();
            for (Element e : children(root, "artifact")) {
                ProviderKind kind = ProviderKind.valueOf(attr(e, "kind").toUpperCase(Locale.ROOT));
                Optional<String> path = optional(e, "file");
                Optional<String> ref = optional(e, "ref");
                artifacts.put(attr(e, "source"), path.isPresent()
                        ? ElevationPlan.Artifact.file(kind, Path.of(path.get()))
                        : ElevationPlan.Artifact.classpath(ref.orElseThrow(
                                () -> new IllegalArgumentException("artifact without file or ref"))));
            }
            Map<String, String> env = new LinkedHashMap<>();
            for (Element e : children(root, "env")) {
                env.put(attr(e, "name"), attr(e, "value"));
            }
            Map<String, String> properties = new LinkedHashMap<>();
            for (Element e : children(root, "property")) {
                properties.put(attr(e, "name"), attr(e, "value"));
            }
            return new ElevationPlan(mode, destination, startedAt, selected, inputs, steps, artifacts, origin, env,
                    properties, ownerUser, ownerGroup, chown);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new IOException("plan file " + file + " is incomplete: " + e.getMessage(), e);
        }
    }

    private static String attr(Element e, String name) {
        if (!e.hasAttribute(name)) {
            throw new IllegalArgumentException("<" + e.getTagName() + "> without " + name);
        }
        return e.getAttribute(name);
    }

    private static Optional<String> optional(Element e, String name) {
        return e.hasAttribute(name) ? Optional.of(e.getAttribute(name)) : Optional.empty();
    }

    private static Element single(Element root, String name, Path file) throws IOException {
        List<Element> found = children(root, name);
        if (found.size() != 1) {
            throw new IOException("plan file " + file + ": expected one <" + name + ">, found " + found.size());
        }
        return found.get(0);
    }

    static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e && name.equals(e.getTagName())) {
                result.add(e);
            }
        }
        return result;
    }

    private static String esc(String text) {
        return XmlText.escape(text);
    }
}
