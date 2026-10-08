package de.yawi.installer.ui.page;

import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.xml.SecureXml;
import javafx.fxml.FXML;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every FXML file and its controller fit together.
 *
 * <p>This guards the defect that made the application unusable: the FXML files
 * referenced handlers such as {@code #goToPage2} that no controller declared, so
 * {@code FXMLLoader.load()} failed at runtime. Both directions are checked:
 * <ul>
 *   <li>every {@code on*="#handler"} must exist as a method on the controller,
 *       otherwise loading the FXML throws;</li>
 *   <li>every {@code @FXML} field of the controller must appear as an
 *       {@code fx:id} in the FXML, otherwise the field stays {@code null} and
 *       causes a {@code NullPointerException} on first use.</li>
 * </ul>
 *
 * <p>Since E03 it also enforces the text convention: user-visible attributes
 * carry {@code %key} references only, every key exists in the base bundle,
 * and {@code %key} appears only on attributes {@code I18nFxml.bindTexts}
 * knows, so no marker can stay visible after a language switch.
 *
 * <p>Runs without a JavaFX toolkit, so it works headless.
 */
class FxmlContractTest {

    /** The frame next to its controller in ui/, the pages in ui/page/. */
    private static final List<Path> FXML_DIRS = List.of(
            Path.of("src/main/resources/de/yawi/installer/ui"),
            Path.of("src/main/resources/de/yawi/installer/ui/page"));

    @Test
    void fxmlFilesExist() throws Exception {
        for (Path dir : FXML_DIRS) {
            assertTrue(Files.isDirectory(dir), "FXML directory not found: " + dir);
        }
        assertTrue(fxmlFiles().size() >= 5, "expected frame + pages, found " + fxmlFiles());
    }

    @Test
    void everyFxmlMatchesItsController() throws Exception {
        List<String> problems = new ArrayList<>();

        for (Path fxml : fxmlFiles()) {
            Document doc = parse(fxml);
            String name = fxml.getFileName().toString();

            String controllerName = findControllerName(doc);
            if (controllerName == null) {
                problems.add(name + ": no fx:controller declared");
                continue;
            }

            Class<?> controller;
            try {
                controller = Class.forName(controllerName);
            } catch (ClassNotFoundException e) {
                problems.add(name + ": controller class not found: " + controllerName);
                continue;
            }

            for (String handler : collectHandlers(doc)) {
                if (!declaresMethod(controller, handler)) {
                    problems.add(name + ": handler #" + handler
                            + " missing in " + controller.getSimpleName());
                }
            }

            Set<String> ids = collectFxIds(doc);
            for (Field field : injectedFields(controller)) {
                if (!ids.contains(field.getName())) {
                    problems.add(name + ": @FXML field '" + field.getName() + "' in "
                            + controller.getSimpleName() + " has no matching fx:id"
                            + " and would stay null");
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "FXML/controller contract violated:\n  " + String.join("\n  ", problems));
    }

    /** Attributes that carry user-visible text and must therefore use {@code %key}. */
    private static final Set<String> TEXT_ATTRIBUTES = Set.of("text", "promptText", "title");

    /**
     * Where {@code I18nFxml.bindTexts} can bind a {@code %key}: element name
     * to attribute. Anything else would keep the marker on screen.
     */
    private static final Map<String, Set<String>> BINDABLE = Map.ofEntries(
            Map.entry("Label", Set.of("text")),
            Map.entry("Button", Set.of("text")),
            Map.entry("CheckBox", Set.of("text")),
            Map.entry("RadioButton", Set.of("text")),
            Map.entry("Hyperlink", Set.of("text")),
            Map.entry("ToggleButton", Set.of("text")),
            Map.entry("TitledPane", Set.of("text")),
            Map.entry("TextField", Set.of("promptText")),
            Map.entry("PasswordField", Set.of("promptText")),
            Map.entry("TextArea", Set.of("promptText")),
            Map.entry("ComboBox", Set.of("promptText")),
            Map.entry("TableColumn", Set.of("text")),
            Map.entry("TreeTableColumn", Set.of("text")),
            Map.entry("Tab", Set.of("text")),
            Map.entry("Tooltip", Set.of("text")));

    private static final Pattern HARD_CODED = Pattern.compile("^[A-Za-z]");

    @Test
    void noUserTextIsHardCoded() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Path fxml : fxmlFiles()) {
            NodeList all = parse(fxml).getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element element = (Element) all.item(i);
                for (String attribute : TEXT_ATTRIBUTES) {
                    String value = element.getAttribute(attribute);
                    if (HARD_CODED.matcher(value).find()) {
                        problems.add(fxml.getFileName() + ": <" + element.getTagName() + " "
                                + attribute + "=\"" + value + "\"> must be a %key");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "hard coded texts in FXML:\n  " + String.join("\n  ", problems));
    }

    @Test
    void everyKeyReferenceExistsAndIsBindable() throws Exception {
        Messages messages = Messages.load();
        List<String> problems = new ArrayList<>();
        for (Path fxml : fxmlFiles()) {
            NodeList all = parse(fxml).getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element element = (Element) all.item(i);
                NamedNodeMap attributes = element.getAttributes();
                for (int a = 0; a < attributes.getLength(); a++) {
                    String attribute = attributes.item(a).getNodeName();
                    String value = attributes.item(a).getNodeValue();
                    if (!value.startsWith("%")) {
                        continue;
                    }
                    String key = value.substring(1);
                    String where = fxml.getFileName() + ": <" + element.getTagName() + " "
                            + attribute + "=\"" + value + "\">";
                    if (!messages.hasKey(key)) {
                        problems.add(where + " key not in messages.properties");
                    }
                    if (!BINDABLE.getOrDefault(element.getTagName(), Set.of()).contains(attribute)) {
                        problems.add(where + " is not bound by I18nFxml, the marker would stay visible");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "%key references:\n  " + String.join("\n  ", problems));
    }

    // --- helpers ---------------------------------------------------------

    private static List<Path> fxmlFiles() throws Exception {
        List<Path> result = new ArrayList<>();
        for (Path dir : FXML_DIRS) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".fxml")).sorted().forEach(result::add);
            }
        }
        return result;
    }

    private static Document parse(Path fxml) throws Exception {
        // getAttribute("fx:controller") matches the qualified name, so the
        // namespace-aware builder from SecureXml works here unchanged.
        return SecureXml.newDocumentBuilder().parse(new File(fxml.toString()));
    }

    private static String findControllerName(Document doc) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            String value = element.getAttribute("fx:controller");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Collects the targets of all event attributes, e.g. onAction="#goToPage2". */
    private static Set<String> collectHandlers(Document doc) {
        Set<String> handlers = new HashSet<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            NamedNodeMap attributes = all.item(i).getAttributes();
            for (int a = 0; a < attributes.getLength(); a++) {
                String attribute = attributes.item(a).getNodeName();
                String value = attributes.item(a).getNodeValue();
                if (attribute.startsWith("on") && value.startsWith("#")) {
                    handlers.add(value.substring(1));
                }
            }
        }
        return handlers;
    }

    private static Set<String> collectFxIds(Document doc) {
        Set<String> ids = new HashSet<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            String id = ((Element) all.item(i)).getAttribute("fx:id");
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * FXML accepts non-public methods and inherited ones, so the whole hierarchy
     * is searched. A handler takes either no argument or the event.
     */
    private static boolean declaresMethod(Class<?> controller, String name) {
        for (Class<?> type = controller; type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Field> injectedFields(Class<?> controller) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = controller; type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(FXML.class)) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }
}
