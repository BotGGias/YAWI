package de.yawi.installer.core.integration;

import java.util.List;
import java.util.Objects;

/**
 * Pure edits of a shell profile's PATH block. The block
 * is fenced by product-specific marker comments so the uninstaller removes
 * exactly what the installer added and nothing the user wrote:
 *
 * <pre>
 * # &gt;&gt;&gt; &lt;productId&gt; PATH (installer) &gt;&gt;&gt;
 * export PATH="&lt;dir1&gt;:&lt;dir2&gt;:$PATH"
 * # &lt;&lt;&lt; &lt;productId&gt; PATH (installer) &lt;&lt;&lt;
 * </pre>
 *
 * Everything here works on strings; the {@code PathStep} reads and writes the
 * files. An existing block is never duplicated (the append is a no-op), and a
 * file is only ever appended to, never rewritten around foreign content.
 */
public final class ShellProfile {

    private ShellProfile() {
    }

    /** The result of removing the block. */
    public record Removal(String content, boolean removed) {
    }

    public static String beginMarker(String productId) {
        return "# >>> " + productId + " PATH (installer) >>>";
    }

    public static String endMarker(String productId) {
        return "# <<< " + productId + " PATH (installer) <<<";
    }

    /** Whether {@code content} already carries this product's block. */
    public static boolean containsBlock(String content, String productId) {
        String begin = beginMarker(productId);
        for (String line : content.split("\n", -1)) {
            if (line.strip().equals(begin)) {
                return true;
            }
        }
        return false;
    }

    /** The block text, with a trailing newline. */
    public static String renderBlock(String productId, List<String> dirs) {
        Objects.requireNonNull(productId, "productId");
        String joined = String.join(":", dirs);
        return beginMarker(productId) + "\n"
                + "# Added by the " + productId + " installer; remove this block to undo.\n"
                + "export PATH=\"" + joined + ":$PATH\"\n"
                + endMarker(productId) + "\n";
    }

    /**
     * Appends the block to {@code content} unless it is already there. A
     * non-empty file that does not end in a newline gets one first, so the
     * block starts on its own line; foreign content is never touched.
     */
    public static String appendBlock(String content, String productId, List<String> dirs) {
        if (containsBlock(content, productId)) {
            return content;
        }
        StringBuilder out = new StringBuilder(content);
        if (!content.isEmpty() && !content.endsWith("\n")) {
            out.append("\n");
        }
        out.append(renderBlock(productId, dirs));
        return out.toString();
    }

    /** Removes exactly this product's block (marker lines included); foreign lines stay. */
    public static Removal removeBlock(String content, String productId) {
        String begin = beginMarker(productId);
        String end = endMarker(productId);
        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inBlock = false;
        boolean removed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inBlock && line.strip().equals(begin)) {
                inBlock = true;
                removed = true;
                continue;
            }
            if (inBlock) {
                if (line.strip().equals(end)) {
                    inBlock = false;
                }
                continue;
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append("\n");
            }
        }
        return new Removal(out.toString(), removed);
    }
}
