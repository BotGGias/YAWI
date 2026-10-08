package de.yawi.installer.core.engine.step;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A minimal reader for POSIX ustar / GNU tar streams - enough for the
 * archives a packager ships: regular files, directories, the ustar prefix
 * and GNU long names. Links and pax headers are reported by type so the
 * caller can skip them. Kept in-house because the JDK has no tar support and
 * the project avoids new dependencies.
 */
final class TarInput {

    static final int BLOCK = 512;

    enum Type { FILE, DIRECTORY, SYMLINK, HARDLINK, OTHER }

    /** One entry's header; read the content through {@link #content()} before calling {@link #next()} again. */
    record Entry(String name, long size, int mode, Type type) {
        boolean isDirectory() {
            return type == Type.DIRECTORY || name.endsWith("/");
        }
    }

    private final InputStream in;
    private final byte[] header = new byte[BLOCK];
    private long remaining;
    private long padding;

    TarInput(InputStream in) {
        this.in = in;
    }

    /** The next entry, or null at the end of the archive. Unread content of the previous entry is skipped. */
    Entry next() throws IOException {
        skipRest();
        String longName = null;
        while (true) {
            if (!readBlock()) {
                return null;
            }
            if (isZero(header)) {
                return null;
            }
            String name = string(0, 100);
            long size = octal(124, 12);
            int mode = (int) octal(100, 8);
            byte typeFlag = header[156];
            String prefix = "ustar".equals(string(257, 5).trim()) ? string(345, 155) : "";
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            if (typeFlag == 'L') {
                // GNU long name: the content is the real name of the next entry.
                longName = readString(size).trim();
                skipRest();
                continue;
            }
            if (typeFlag == 'x' || typeFlag == 'g') {
                // pax headers: not interpreted; the entry keeps its (possibly truncated) ustar name.
                remaining = size;
                padding = pad(size);
                skipRest();
                continue;
            }
            if (longName != null) {
                name = longName;
            }
            Type type = switch (typeFlag) {
                case '0', 0, '7' -> Type.FILE;
                case '5' -> Type.DIRECTORY;
                case '2' -> Type.SYMLINK;
                case '1' -> Type.HARDLINK;
                default -> Type.OTHER;
            };
            remaining = type == Type.DIRECTORY ? 0 : size;
            padding = pad(remaining);
            return new Entry(name, size, mode, type);
        }
    }

    /** The current entry's content; reading past its size yields end of stream. */
    InputStream content() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int b = in.read();
                if (b >= 0) {
                    remaining--;
                }
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int n = in.read(buf, off, (int) Math.min(len, remaining));
                if (n > 0) {
                    remaining -= n;
                }
                return n;
            }
        };
    }

    private void skipRest() throws IOException {
        skipFully(remaining + padding);
        remaining = 0;
        padding = 0;
    }

    private String readString(long size) throws IOException {
        byte[] bytes = in.readNBytes((int) size);
        remaining = 0;
        padding = pad(size);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private boolean readBlock() throws IOException {
        int n = in.readNBytes(header, 0, BLOCK);
        if (n == 0) {
            return false;
        }
        if (n < BLOCK) {
            throw new IOException("truncated tar header");
        }
        return true;
    }

    private void skipFully(long count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("truncated tar entry");
                }
                skipped = 1;
            }
            count -= skipped;
        }
    }

    private static long pad(long size) {
        long rest = size % BLOCK;
        return rest == 0 ? 0 : BLOCK - rest;
    }

    private String string(int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private long octal(int offset, int length) throws IOException {
        String text = string(offset, length).trim();
        if (text.isEmpty()) {
            return 0;
        }
        if ((header[offset] & 0x80) != 0) {
            // GNU base-256 for sizes over 8 GiB - not expected in an installer payload.
            throw new IOException("base-256 tar fields are not supported");
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new IOException("bad octal field in tar header: '" + text + "'");
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    static boolean looksLikeGzip(byte[] head) {
        return head.length >= 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b;
    }

    static boolean looksLikeZip(byte[] head) {
        return head.length >= 4 && Arrays.equals(Arrays.copyOf(head, 4), new byte[] {'P', 'K', 3, 4});
    }
}
