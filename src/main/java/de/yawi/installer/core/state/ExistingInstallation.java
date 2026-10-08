package de.yawi.installer.core.state;

import de.yawi.installer.core.state.InstallationRegistry.Registration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A register entry checked against the disk: whether the
 * destination and its record are still there and belong to this product.
 * Anything but {@link Status#INTACT} is an orphaned entry that the
 * maintenance page offers to remove.
 *
 * @param record the record under the destination, present only when intact
 * @param detail the technical reason for a non-intact status, for the log
 */
public record ExistingInstallation(Registration registration, Optional<InstallationRecord> record, Status status,
                                   String detail) {

    public enum Status {
        INTACT,
        /** The destination folder is gone. */
        DESTINATION_MISSING,
        /** The folder exists, {@code .installer/record.xml} does not. */
        RECORD_MISSING,
        /** The record cannot be parsed. */
        RECORD_UNREADABLE,
        /** The record belongs to another product. */
        FOREIGN_RECORD
    }

    public ExistingInstallation {
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
    }

    /** Reads the destination's record and classifies the entry; never throws. */
    public static ExistingInstallation inspect(Registration registration, String productId) {
        Path destination = registration.destination();
        if (!Files.isDirectory(destination)) {
            return new ExistingInstallation(registration, Optional.empty(), Status.DESTINATION_MISSING,
                    "destination " + destination + " does not exist");
        }
        Path file = RecordWriter.defaultFile(destination);
        if (!Files.isRegularFile(file)) {
            return new ExistingInstallation(registration, Optional.empty(), Status.RECORD_MISSING,
                    "no record at " + file);
        }
        InstallationRecord record;
        try {
            record = RecordReader.read(file);
        } catch (IOException | RuntimeException e) {
            // The reader throws NumberFormatException for a missing version attribute.
            return new ExistingInstallation(registration, Optional.empty(), Status.RECORD_UNREADABLE,
                    "record " + file + " unusable: " + e.getMessage());
        }
        if (!record.productId().equals(productId)) {
            return new ExistingInstallation(registration, Optional.empty(), Status.FOREIGN_RECORD,
                    "record " + file + " is for product '" + record.productId() + "', expected '" + productId + "'");
        }
        return new ExistingInstallation(registration, Optional.of(record), Status.INTACT, "");
    }

    public boolean isIntact() {
        return status == Status.INTACT;
    }

    public boolean isOrphaned() {
        return !isIntact();
    }

    public Path destination() {
        return registration.destination();
    }

    /** The version on disk when intact, else what the register remembered. */
    public String installedVersion() {
        return record.map(InstallationRecord::productVersion).orElse(registration.productVersion());
    }
}
