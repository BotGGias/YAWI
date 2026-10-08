package de.yawi.installer.core.elevation;

import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.Rollback;
import de.yawi.installer.core.engine.StepOutcome;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.StepFailedException;
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
 * {@code result.xml}: how the elevated segment ended. It is the
 * source of truth - {@code osascript} loses the child's exit code, and every
 * strategy's own code only tells what happened before the child ran. The
 * failure travels as code, bundle key and arguments (like any
 * {@link InstallerException}), so the parent renders it in the user's
 * language.
 *
 * @param reports  one per step the segment ran, in order
 * @param rollback the segment's own rollback, if one ran
 */
public record ElevatedResult(boolean succeeded, Optional<Failure> failure, List<Report> reports,
                             Optional<Rollback.Report> rollback) {

    public static final int FORMAT_VERSION = 1;

    public record Failure(ErrorCode code, String messageKey, List<String> args, String technical,
                          Optional<String> detail, Optional<String> stepId, int exitStatus) {
        public Failure {
            args = List.copyOf(args);
        }

        public static Failure of(InstallerException e) {
            List<String> args = new ArrayList<>();
            for (Object arg : e.userArgs()) {
                args.add(String.valueOf(arg));
            }
            String stepId = e instanceof StepFailedException s ? s.stepId() : null;
            int status = e instanceof StepFailedException s ? s.exitStatus() : StepFailedException.NO_EXIT_STATUS;
            return new Failure(e.code(), e.messageKey(), args, e.getMessage(), e.detail(),
                    Optional.ofNullable(stepId), status);
        }

        /** The failure as it was on the other side, as far as the parent needs it. */
        public InstallerException toException() {
            if (code == ErrorCode.CANCELLED) {
                return new CancelledException(technical, null);
            }
            if (code == ErrorCode.STEP_FAILED && stepId.isPresent()) {
                return new StepFailedException(stepId.get(), reason(), exitStatus, detail.orElse(null), null);
            }
            return new RemoteFailure(this);
        }

        /** {@link StepFailedException}'s user arguments are step id and reason. */
        private String reason() {
            return args.size() > 1 ? args.get(1) : technical;
        }
    }

    /** A failure of the elevated process, replayed with its original code, text and detail. */
    public static final class RemoteFailure extends InstallerException {
        RemoteFailure(Failure failure) {
            super(failure.code(), failure.messageKey(), failure.args().toArray(), failure.technical(),
                    failure.detail().orElse(null), null);
        }
    }

    public record Report(String stepId, StepOutcome outcome, Optional<String> message) {
    }

    public ElevatedResult {
        reports = List.copyOf(reports);
    }

    public static ElevatedResult of(Engine.Result result) {
        List<Report> reports = new ArrayList<>();
        for (Engine.StepReport r : result.reports()) {
            reports.add(new Report(r.step().id(), r.outcome(), r.failure().map(Throwable::getMessage)));
        }
        return new ElevatedResult(result.succeeded(), result.failure().map(Failure::of), reports, result.rollback());
    }

    /** A run that never got to its steps (the plan could not be read, the record not opened). */
    public static ElevatedResult failed(InstallerException e) {
        return new ElevatedResult(false, Optional.of(Failure.of(e)), List.of(), Optional.empty());
    }

    public boolean cancelled() {
        return failure.map(f -> f.code() == ErrorCode.CANCELLED).orElse(false);
    }

    public int exitCode() {
        return succeeded ? 0 : failure.map(f -> f.code().exitCode()).orElse(ErrorCode.GENERAL.exitCode());
    }

    // --- XML ---------------------------------------------------------------

    public String format() {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<result version=\"").append(FORMAT_VERSION).append("\" succeeded=\"").append(succeeded)
                .append("\" exitCode=\"").append(exitCode()).append("\">\n");
        failure.ifPresent(f -> {
            out.append("  <failure code=\"").append(f.code().name()).append("\" messageKey=\"").append(esc(f.messageKey()))
                    .append("\" technical=\"").append(esc(f.technical())).append("\" exitStatus=\"").append(f.exitStatus()).append('"');
            f.stepId().ifPresent(id -> out.append(" stepId=\"").append(esc(id)).append('"'));
            out.append(">\n");
            f.args().forEach(a -> out.append("    <arg>").append(esc(a)).append("</arg>\n"));
            f.detail().ifPresent(d -> out.append("    <detail>").append(escText(d)).append("</detail>\n"));
            out.append("  </failure>\n");
        });
        reports.forEach(r -> {
            out.append("  <report step=\"").append(esc(r.stepId())).append("\" outcome=\"").append(r.outcome().name()).append('"');
            r.message().ifPresent(m -> out.append(" message=\"").append(esc(m)).append('"'));
            out.append("/>\n");
        });
        rollback.ifPresent(r -> {
            out.append("  <rollback restored=\"").append(r.restored()).append("\" deleted=\"").append(r.deleted()).append("\">\n");
            r.stepsWithoutReverse().forEach(s -> out.append("    <noReverse>").append(esc(s)).append("</noReverse>\n"));
            r.failures().forEach(s -> out.append("    <failure>").append(esc(s)).append("</failure>\n"));
            r.leftBehind().forEach(p -> out.append("    <leftBehind>").append(esc(p.toString())).append("</leftBehind>\n"));
            out.append("  </rollback>\n");
        });
        out.append("</result>\n");
        return out.toString();
    }

    public void write(Path file) throws IOException {
        Files.writeString(file, format(), StandardCharsets.UTF_8);
    }

    /** @return empty if the file is empty (the child never got that far) */
    public static Optional<ElevatedResult> read(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return Optional.empty();
        }
        Document document;
        try {
            document = SecureXml.newDocumentBuilder().parse(file.toFile());
        } catch (SAXException e) {
            throw new IOException("result file " + file + " is not well-formed: " + e.getMessage(), e);
        }
        Element root = document.getDocumentElement();
        try {
            boolean succeeded = Boolean.parseBoolean(root.getAttribute("succeeded"));
            Optional<Failure> failure = Optional.empty();
            List<Element> failures = PlanFile.children(root, "failure");
            if (!failures.isEmpty()) {
                Element f = failures.get(0);
                List<String> args = new ArrayList<>();
                PlanFile.children(f, "arg").forEach(a -> args.add(a.getTextContent()));
                Optional<String> detail = PlanFile.children(f, "detail").stream().findFirst().map(Element::getTextContent);
                failure = Optional.of(new Failure(ErrorCode.valueOf(f.getAttribute("code")), f.getAttribute("messageKey"),
                        args, f.getAttribute("technical"), detail,
                        f.hasAttribute("stepId") ? Optional.of(f.getAttribute("stepId")) : Optional.empty(),
                        Integer.parseInt(f.getAttribute("exitStatus"))));
            }
            List<Report> reports = new ArrayList<>();
            for (Element r : PlanFile.children(root, "report")) {
                reports.add(new Report(r.getAttribute("step"), StepOutcome.valueOf(r.getAttribute("outcome")),
                        r.hasAttribute("message") ? Optional.of(r.getAttribute("message")) : Optional.empty()));
            }
            Optional<Rollback.Report> rollback = Optional.empty();
            List<Element> rollbacks = PlanFile.children(root, "rollback");
            if (!rollbacks.isEmpty()) {
                Element r = rollbacks.get(0);
                List<String> noReverse = new ArrayList<>();
                PlanFile.children(r, "noReverse").forEach(e -> noReverse.add(e.getTextContent()));
                List<String> rollbackFailures = new ArrayList<>();
                PlanFile.children(r, "failure").forEach(e -> rollbackFailures.add(e.getTextContent()));
                List<Path> leftBehind = new ArrayList<>();
                PlanFile.children(r, "leftBehind").forEach(e -> leftBehind.add(Path.of(e.getTextContent())));
                rollback = Optional.of(new Rollback.Report(Integer.parseInt(r.getAttribute("restored")),
                        Integer.parseInt(r.getAttribute("deleted")), noReverse, rollbackFailures, leftBehind));
            }
            return Optional.of(new ElevatedResult(succeeded, failure, reports, rollback));
        } catch (IllegalArgumentException e) {
            throw new IOException("result file " + file + " is incomplete: " + e.getMessage(), e);
        }
    }

    private static String esc(String text) {
        return XmlText.escape(text);
    }

    /** Element content that keeps its line breaks (the detail pane is multi-line). */
    private static String escText(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\n' -> sb.append('\n');
                default -> sb.append(c < 0x20 && c != '\t' ? ' ' : c);
            }
        }
        return sb.toString();
    }
}
