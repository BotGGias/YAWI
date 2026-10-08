package de.yawi.installer.core.download;

import java.net.URI;

/** What a download reports; called on the downloading thread, at most about ten times per second. */
public interface DownloadListener {

    DownloadListener NOOP = new DownloadListener() {
    };

    /** An attempt begins; {@code resumeFrom} is the byte offset of the part file, 0 for a fresh start. */
    default void started(URI url, long resumeFrom) {
    }

    default void progress(DownloadProgress progress) {
    }

    /** An attempt failed and will be repeated after a pause. */
    default void retry(URI url, int attempt, Throwable cause) {
    }

    /**
     * Data that was not downloaded is being read for its checksum (E10-S01);
     * {@link #progress} follows with the bytes read. {@code total} is
     * {@code -1} if unknown.
     */
    default void verifying(String fileName, long total) {
    }

    /** A provider failed and the next one is about to be tried (E08-S03); {@code reason} is the failure's message. */
    default void fallback(ProviderKind from, ProviderKind to, String reason) {
    }
}
