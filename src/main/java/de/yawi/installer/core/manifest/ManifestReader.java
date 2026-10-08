package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a schema-valid DOM into the {@link InstallManifest} records.
 *
 * <p>Only what the XSD cannot express is checked here: the children a
 * {@code <step>} or {@code <provider>} needs for its {@code @type}. Such
 * findings are collected as {@link ManifestProblem}s naming the element's id,
 * and the offending element is left out of the model; the parser refuses the
 * whole manifest afterwards.
 */
final class ManifestReader {

    private final List<ManifestProblem> problems = new ArrayList<>();

    List<ManifestProblem> problems() {
        return problems;
    }

    InstallManifest read(Document doc, ManifestOrigin origin) {
        return read(doc, origin, new byte[0]);
    }

    InstallManifest read(Document doc, ManifestOrigin origin, byte[] xmlBytes) {
        Element root = doc.getDocumentElement();
        int schemaVersion = Integer.parseInt(attr(root, "schemaVersion"));

        Element sources = child(root, "sources").orElse(null);
        Element presets = child(root, "presets").orElse(null);
        Element steps = child(root, "steps").orElse(null);

        return new InstallManifest(
                schemaVersion,
                origin,
                readProduct(required(root, "product")),
                readLanguages(required(root, "languages")),
                readWizard(required(root, "wizard")),
                readDestination(required(root, "destination")),
                sources == null ? List.of() : children(sources, "source").stream().map(this::readSource).toList(),
                children(required(root, "components"), "component").stream().map(this::readComponent).toList(),
                presets == null ? List.of() : children(presets, "preset").stream().map(this::readPreset).toList(),
                steps == null ? List.of() : children(steps, "step").stream()
                        .map(this::readStep).flatMap(Optional::stream).toList(),
                child(root, "integration").map(this::readIntegration).orElse(IntegrationConfig.NONE),
                child(root, "i18n").map(this::readMessages).orElse(Map.of()),
                xmlBytes);
    }

    // --- blocks ----------------------------------------------------------

    private Product readProduct(Element e) {
        return new Product(
                attr(e, "id"),
                attr(e, "version"),
                text(e, "name"),
                textOrNull(e, "vendor"),
                textOrNull(e, "homepage"),
                textOrNull(e, "icon"));
    }

    private LanguageConfig readLanguages(Element e) {
        List<Locale> languages = children(e, "language").stream()
                .map(l -> Locale.forLanguageTag(attr(l, "code")))
                .toList();
        return new LanguageConfig(Locale.forLanguageTag(attr(e, "default")), languages);
    }

    private WizardConfig readWizard(Element e) {
        List<String> pages = children(e, "page").stream().map(p -> attr(p, "name")).toList();
        // The XSD guarantees positive integers when the attributes are present.
        int width = e.hasAttribute("width") ? Integer.parseInt(attr(e, "width")) : WizardConfig.DEFAULT_WIDTH;
        int height = e.hasAttribute("height") ? Integer.parseInt(attr(e, "height")) : WizardConfig.DEFAULT_HEIGHT;
        return new WizardConfig(pages, textOrNull(e, "license"), width, height);
    }

    private DestinationConfig readDestination(Element e) {
        return new DestinationConfig(
                bool(e, "allowUserChange", true),
                size(e, "minFreeBytes"),
                osTexts(e, "default"),
                osTexts(e, "systemWide"));
    }

    private Source readSource(Element e) {
        String id = attr(e, "id");
        List<Provider> providers = children(e, "provider").stream()
                .map(p -> readProvider(p, id))
                .flatMap(Optional::stream)
                .toList();
        URI versionCheck = child(e, "versionCheck").map(v -> URI.create(attr(v, "url"))).orElse(null);
        return new Source(id, size(e, "size"), attrOrNull(e, "sha256"), providers, versionCheck);
    }

    private static final List<String> TORRENT_ATTRIBUTES = List.of("port", "peerTimeout", "fallback", "lsd", "pex");

    private Optional<Provider> readProvider(Element e, String sourceId) {
        String type = attr(e, "type");
        String subject = "source '" + sourceId + "'";
        if (!type.equals("torrent")) {
            for (String name : TORRENT_ATTRIBUTES) {
                if (e.hasAttribute(name)) {
                    return missing(subject, "attribute '" + name + "' only applies to type=\"torrent\"");
                }
            }
        }
        switch (type) {
            case "bundled" -> {
                Optional<String> path = child(e, "path").map(ManifestReader::text);
                if (path.isEmpty()) {
                    return missing(subject, "bundled provider needs a <path>");
                }
                return Optional.of(new Provider.Bundled(path.get()));
            }
            case "http" -> {
                List<URI> urls = children(e, "url").stream().map(u -> URI.create(text(u))).toList();
                if (urls.isEmpty()) {
                    return missing(subject, "http provider needs at least one <url>");
                }
                return Optional.of(new Provider.Http(urls));
            }
            case "torrent" -> {
                String magnet = textOrNull(e, "magnet");
                String torrentFile = textOrNull(e, "torrentFile");
                if ((magnet == null) == (torrentFile == null)) {
                    return missing(subject, "torrent provider needs either <magnet> or <torrentFile>");
                }
                int port = e.hasAttribute("port") ? Integer.parseInt(attr(e, "port")) : Provider.Torrent.DEFAULT_PORT;
                Duration peerTimeout = e.hasAttribute("peerTimeout")
                        ? Durations.parse(attr(e, "peerTimeout")) : Provider.Torrent.DEFAULT_PEER_TIMEOUT;
                Provider.Torrent.Fallback fallback = e.hasAttribute("fallback")
                        ? Provider.Torrent.Fallback.valueOf(attr(e, "fallback").toUpperCase(java.util.Locale.ROOT))
                        : Provider.Torrent.Fallback.AUTO;
                boolean lsd = !e.hasAttribute("lsd") || Boolean.parseBoolean(attr(e, "lsd")) || attr(e, "lsd").equals("1");
                boolean pex = !e.hasAttribute("pex") || Boolean.parseBoolean(attr(e, "pex")) || attr(e, "pex").equals("1");
                return Optional.of(new Provider.Torrent(magnet, torrentFile, port, peerTimeout, fallback, lsd, pex));
            }
            default -> throw new IllegalStateException("provider type not covered by schema: " + type);
        }
    }

    private Component readComponent(Element e) {
        return new Component(
                attr(e, "id"),
                bool(e, "required", false),
                bool(e, "selectedByDefault", false),
                size(e, "size"),
                text(e, "name"),
                textOrNull(e, "description"),
                refs(e, "dependsOn"),
                osRefs(e, "uses"),
                osRefs(e, "step"),
                children(e, "input").stream().map(this::readInput).toList());
    }

    private ComponentInput readInput(Element e) {
        List<ComponentInput.Option> options = children(e, "option").stream()
                .map(o -> new ComponentInput.Option(attr(o, "value"), o.getTextContent().trim()))
                .toList();
        return new ComponentInput(
                attr(e, "id"),
                ComponentInput.InputType.fromManifestName(attr(e, "type")),
                attrOrNull(e, "default"),
                longOrNull(e, "min"),
                longOrNull(e, "max"),
                text(e, "label"),
                bool(e, "required", false),
                options);
    }

    private Preset readPreset(Element e) {
        return new Preset(attr(e, "id"), refs(e, "component"));
    }

    private Optional<InstallStep> readStep(Element e) {
        String id = attr(e, "id");
        String type = attr(e, "type");
        int weight = Integer.parseInt(attrOr(e, "weight", "1"));
        String subject = "step '" + id + "'";

        switch (type) {
            case "extract" -> {
                Optional<String> archive = child(e, "archive").map(a -> attr(a, "ref"));
                Optional<String> to = child(e, "to").map(ManifestReader::text);
                if (archive.isEmpty() || to.isEmpty()) {
                    return missing(subject, "extract step needs <archive ref> and <to>");
                }
                return Optional.of(new InstallStep.Extract(id, weight, archive.get(), to.get()));
            }
            case "template", "copy" -> {
                Optional<String> from = child(e, "from").map(ManifestReader::text);
                Optional<String> to = child(e, "to").map(ManifestReader::text);
                if (from.isEmpty() || to.isEmpty()) {
                    return missing(subject, type + " step needs <from> and <to>");
                }
                if (type.equals("copy")) {
                    return Optional.of(new InstallStep.Copy(id, weight, from.get(), to.get()));
                }
                Map<String, String> replacements = new LinkedHashMap<>();
                for (Element r : children(e, "replace")) {
                    replacements.put(attr(r, "key"), attr(r, "value"));
                }
                return Optional.of(new InstallStep.Template(id, weight, from.get(), to.get(), replacements,
                        attrOr(e, "encoding", "UTF-8")));
            }
            case "mkdir" -> {
                Optional<String> to = child(e, "to").map(ManifestReader::text);
                if (to.isEmpty()) {
                    return missing(subject, "mkdir step needs <to>");
                }
                return Optional.of(new InstallStep.Mkdir(id, weight, to.get()));
            }
            case "chmod" -> {
                Optional<String> to = child(e, "to").map(ManifestReader::text);
                String mode = attrOrNull(e, "mode");
                if (to.isEmpty() || mode == null) {
                    return missing(subject, "chmod step needs <to> and @mode");
                }
                return Optional.of(new InstallStep.Chmod(id, weight, to.get(), mode));
            }
            case "run-command" -> {
                Map<OperatingSystem, List<Command>> commands = commandsByOs(e, "commands");
                if (commands.isEmpty()) {
                    return missing(subject, "run-command step needs at least one <commands os>");
                }
                Map<String, String> env = new LinkedHashMap<>();
                for (Element v : children(e, "env")) {
                    env.put(attr(v, "name"), attr(v, "value"));
                }
                return Optional.of(new InstallStep.RunCommand(
                        id, weight,
                        Integer.parseInt(attrOr(e, "expectExitCode", "0")),
                        Integer.parseInt(attrOr(e, "timeoutSeconds", "0")),
                        InstallStep.OnFailure.fromManifestName(attrOr(e, "onFailure", "abort")),
                        bool(e, "elevated", false),
                        textOrNull(e, "workingDir"),
                        env,
                        commands,
                        commandsByOs(e, "rollback")));
            }
            default -> throw new IllegalStateException("step type not covered by schema: " + type);
        }
    }

    private IntegrationConfig readIntegration(Element e) {
        List<IntegrationConfig.Shortcut> shortcuts = children(e, "shortcut").stream()
                .map(s -> new IntegrationConfig.Shortcut(
                        attr(s, "id"), bool(s, "desktop", false), bool(s, "menu", false),
                        text(s, "name"), text(s, "target"), textOrNull(s, "icon")))
                .toList();
        List<String> pathEntries = children(e, "pathEntry").stream().map(ManifestReader::text).toList();
        List<IntegrationConfig.FileAssociation> associations = children(e, "fileAssociation").stream()
                .map(a -> new IntegrationConfig.FileAssociation(
                        attr(a, "extension"), attr(a, "target"), textOrNull(a, "description"),
                        bool(a, "default", true)))
                .toList();
        return new IntegrationConfig(shortcuts, pathEntries, associations);
    }

    private Map<Locale, Map<String, String>> readMessages(Element e) {
        Map<Locale, Map<String, String>> result = new LinkedHashMap<>();
        for (Element messages : children(e, "messages")) {
            Map<String, String> texts = new LinkedHashMap<>();
            for (Element m : children(messages, "message")) {
                texts.put(attr(m, "path"), text(m));
            }
            result.put(Locale.forLanguageTag(attr(messages, "lang")), texts);
        }
        return result;
    }

    // --- helpers ---------------------------------------------------------

    private <T> Optional<T> missing(String subject, String message) {
        problems.add(ManifestProblem.error(subject, message));
        return Optional.empty();
    }

    private Map<OperatingSystem, List<Command>> commandsByOs(Element step, String name) {
        Map<OperatingSystem, List<Command>> result = new EnumMap<>(OperatingSystem.class);
        for (Element block : children(step, name)) {
            List<Command> commands = children(block, "command").stream()
                    .map(c -> new Command(children(c, "arg").stream().map(ManifestReader::text).toList()))
                    .toList();
            result.put(OperatingSystem.fromManifestName(attr(block, "os")), commands);
        }
        return result;
    }

    private static Map<OperatingSystem, String> osTexts(Element parent, String name) {
        Map<OperatingSystem, String> result = new EnumMap<>(OperatingSystem.class);
        for (Element e : children(parent, name)) {
            result.put(OperatingSystem.fromManifestName(attr(e, "os")), text(e));
        }
        return result;
    }

    private static List<String> refs(Element parent, String name) {
        return children(parent, name).stream().map(r -> attr(r, "ref")).toList();
    }

    /** As {@link #refs}, but keeps the optional {@code @os} attribute; absent means every OS. */
    private static List<OsRef> osRefs(Element parent, String name) {
        return children(parent, name).stream()
                .map(r -> new OsRef(attr(r, "ref"),
                        r.hasAttribute("os") ? OperatingSystem.fromManifestName(attr(r, "os")) : null))
                .toList();
    }

    private static long size(Element e, String name) {
        String value = attrOrNull(e, name);
        return value == null ? -1 : ByteSize.parse(value);
    }

    private static Long longOrNull(Element e, String name) {
        String value = attrOrNull(e, name);
        return value == null ? null : Long.valueOf(value);
    }

    private static boolean bool(Element e, String name, boolean fallback) {
        String value = attrOrNull(e, name);
        // xs:boolean also allows 1/0
        return value == null ? fallback : value.equals("true") || value.equals("1");
    }

    private static String attr(Element e, String name) {
        String value = attrOrNull(e, name);
        if (value == null) {
            throw new IllegalStateException("<" + e.getTagName() + "> lacks @" + name + " despite schema");
        }
        return value;
    }

    private static String attrOr(Element e, String name, String fallback) {
        String value = attrOrNull(e, name);
        return value == null ? fallback : value;
    }

    private static String attrOrNull(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name).trim() : null;
    }

    private static Element required(Element parent, String name) {
        return child(parent, name).orElseThrow(
                () -> new IllegalStateException("<" + parent.getTagName() + "> lacks <" + name + "> despite schema"));
    }

    private static String text(Element parent, String childName) {
        return text(required(parent, childName));
    }

    private static String textOrNull(Element parent, String childName) {
        return child(parent, childName).map(ManifestReader::text).orElse(null);
    }

    private static String text(Element e) {
        return e.getTextContent().trim();
    }

    private static Optional<Element> child(Element parent, String name) {
        List<Element> all = children(parent, name);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Direct child elements with the given name, in document order. */
    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(name)) {
                result.add((Element) node);
            }
        }
        return result;
    }
}
