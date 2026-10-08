package de.yawi.installer.core.error;

import java.util.List;

/**
 * An uninstall could not remove every item; the rest was removed,
 * the record and the register entry stayed for a second attempt. The user
 * text names the count, the detail pane the items.
 */
public class UninstallFailedException extends InstallerException {

    private final List<String> failures;

    /** @param failures one line per item that could not be removed, from {@code Uninstaller.Report#failures()} */
    public UninstallFailedException(List<String> failures) {
        super(ErrorCode.GENERAL, "error.uninstall", new Object[] {failures.size()},
                failures.size() + " item(s) could not be removed", String.join("\n", failures), null);
        this.failures = List.copyOf(failures);
    }

    public List<String> failures() {
        return failures;
    }
}
