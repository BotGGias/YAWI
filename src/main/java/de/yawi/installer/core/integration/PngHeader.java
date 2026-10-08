package de.yawi.installer.core.integration;

import java.util.Optional;

/** Reads width and height from the start of a PNG file, for the icon theme's size folder. */
public final class PngHeader {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    /** Signature, IHDR length and type, then width and height as big-endian 32-bit values. */
    private static final int MIN_LENGTH = 24;

    private PngHeader() {
    }

    /** {@code [width, height]} if the bytes start a PNG, else empty. */
    public static Optional<int[]> size(byte[] head) {
        if (head == null || head.length < MIN_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (head[i] != SIGNATURE[i]) {
                return Optional.empty();
            }
        }
        if (head[12] != 'I' || head[13] != 'H' || head[14] != 'D' || head[15] != 'R') {
            return Optional.empty();
        }
        int width = readInt(head, 16);
        int height = readInt(head, 20);
        return width > 0 && height > 0 ? Optional.of(new int[] {width, height}) : Optional.empty();
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }
}
