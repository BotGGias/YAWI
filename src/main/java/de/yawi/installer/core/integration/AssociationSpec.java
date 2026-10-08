package de.yawi.installer.core.integration;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A file association with every placeholder resolved: which extension
 * opens which target, from where, under which label. The generated MIME type
 * and the Windows ProgId are product-unique, so the installed application
 * becomes the de-facto handler for the type.
 *
 * @param productId   the manifest's product id; part of the generated names
 * @param extension   the file extension including the dot, e.g. {@code .sumap}
 * @param target      the program to open the file with (inside the destination)
 * @param workingDir  the launched program's working directory, normally the destination
 * @param description the visible type description, if the manifest gave one
 */
public record AssociationSpec(String productId, String extension, Path target, Path workingDir,
                              Optional<String> description) {

    public AssociationSpec {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(extension, "extension");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(description, "description");
    }

    /** The extension without the leading dot, e.g. {@code sumap}. */
    public String extensionNoDot() {
        return noDot(extension);
    }

    /** The generated MIME type, product-unique: {@code application/x-vnd.<productId>-<ext>}. */
    public String mimeType() {
        return mimeType(productId, extension);
    }

    /** The Windows ProgId, product-unique: {@code <ProductId>.<ext>}. */
    public String progId() {
        return progId(productId, extension);
    }

    /** The base file name of the Linux MIME package and handler {@code .desktop}. */
    public String baseName() {
        return baseName(productId, extension);
    }

    /** A visible label: the manifest's description, else the product id and the extension. */
    public String label() {
        return description.filter(s -> !s.isBlank()).orElse(productId + " " + extension);
    }

    // --- pure name derivations, usable without a resolved target (the uninstaller needs them) ---

    public static String mimeType(String productId, String extension) {
        return "application/x-vnd." + media(productId) + "-" + media(noDot(extension));
    }

    public static String progId(String productId, String extension) {
        return alnum(productId) + "." + alnum(noDot(extension));
    }

    public static String baseName(String productId, String extension) {
        return media(productId) + "-" + media(noDot(extension));
    }

    private static String noDot(String extension) {
        return extension.startsWith(".") ? extension.substring(1) : extension;
    }

    /** Lower-case, only the characters a media subtype and a file name safely share. */
    private static String media(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
        return cleaned.isEmpty() ? "app" : cleaned;
    }

    /** Only letters and digits, for a Windows ProgId component. */
    private static String alnum(String text) {
        String cleaned = text.replaceAll("[^A-Za-z0-9]+", "");
        return cleaned.isEmpty() ? "App" : cleaned;
    }
}
