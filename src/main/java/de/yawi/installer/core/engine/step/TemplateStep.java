package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.Placeholders;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.ResourceRef;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code template}: reads a text file, replaces {@code ${KEY}} with the
 * step's {@code <replace>} values (which may themselves carry installer
 * placeholders) and writes the result. Keys without a replacement stay as
 * they are, so a template may contain other {@code ${...}} syntax.
 */
final class TemplateStep extends AbstractFileStep {

    private final InstallStep.Template step;

    TemplateStep(InstallStep.Template step) {
        super(step);
        this.step = step;
    }

    @Override
    void run(ExecutionContext context) throws IOException {
        Charset charset = charset();
        Path to = target(context, step.to());
        String from = context.resolve(step.from());
        context.listener().stepProgress(-1, to.toString());

        String text;
        try (InputStream in = ResourceRef.open(from)) {
            text = new String(in.readAllBytes(), charset);
        }
        Map<String, String> values = new LinkedHashMap<>();
        step.replacements().forEach((key, value) -> values.put(key, context.resolve(value)));
        String result = Placeholders.ofValues(values).resolve(text);

        if (to.getParent() != null) {
            createDirectories(context, to.getParent());
        }
        Prepared prepared = prepareTarget(context, to);
        Files.writeString(to, result, charset);
        written(context, to, prepared);
        context.listener().stepProgress(1, to.toString());
    }

    private Charset charset() {
        try {
            return Charset.forName(step.encoding());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new StepFailedException(id(), "unknown encoding '" + step.encoding() + "'", e);
        }
    }

    @Override
    public String describe(ExecutionContext context) {
        return "template " + context.resolve(step.from()) + " -> " + context.resolve(step.to())
                + " " + step.replacements().keySet();
    }
}
