package de.yawi.installer.core.platform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformDetectionTest {

    private static final Environment ENV = Environment.of(Map.of(), Map.of());

    @Test
    void recognisesOperatingSystemSpellings() {
        Map<String, OperatingSystem> table = new java.util.HashMap<>();
        table.put("Windows 10", OperatingSystem.WINDOWS);
        table.put("Windows 11", OperatingSystem.WINDOWS);
        table.put("Windows Server 2022", OperatingSystem.WINDOWS);
        table.put("windows 7", OperatingSystem.WINDOWS);
        table.put("Linux", OperatingSystem.LINUX);
        table.put("LINUX", OperatingSystem.LINUX);
        table.put("Mac OS X", OperatingSystem.MACOS);
        table.put("macOS", OperatingSystem.MACOS);
        table.put("Darwin", OperatingSystem.MACOS);
        table.put("FreeBSD", OperatingSystem.UNKNOWN);
        table.put("SunOS", OperatingSystem.UNKNOWN);
        table.put("", OperatingSystem.UNKNOWN);
        table.put(null, OperatingSystem.UNKNOWN);
        table.forEach((name, expected) ->
                assertEquals(expected, OperatingSystem.fromOsName(name), String.valueOf(name)));
    }

    @Test
    void recognisesArchitectureSpellings() {
        Map<String, Architecture> table = new java.util.HashMap<>();
        table.put("amd64", Architecture.X64);
        table.put("x86_64", Architecture.X64);
        table.put("x64", Architecture.X64);
        table.put("AMD64", Architecture.X64);
        table.put("aarch64", Architecture.AARCH64);
        table.put("arm64", Architecture.AARCH64);
        table.put("x86", Architecture.UNKNOWN);
        table.put("i386", Architecture.UNKNOWN);
        table.put("riscv64", Architecture.UNKNOWN);
        table.put("arm", Architecture.UNKNOWN);
        table.put(null, Architecture.UNKNOWN);
        table.forEach((name, expected) ->
                assertEquals(expected, Architecture.fromOsArch(name), String.valueOf(name)));
    }

    @Test
    void manifestNamesMatchThePlaceholderValues() {
        assertEquals("x64", Architecture.X64.manifestName().orElseThrow());
        assertEquals("aarch64", Architecture.AARCH64.manifestName().orElseThrow());
        assertTrue(Architecture.UNKNOWN.manifestName().isEmpty());
    }

    @Test
    void detectBuildsTheMatchingImplementation() {
        assertInstanceOf(WindowsPlatform.class, PlatformFactory.detect("Windows 11", "amd64", ENV));
        assertInstanceOf(LinuxPlatform.class, PlatformFactory.detect("Linux", "aarch64", ENV));
        assertInstanceOf(MacPlatform.class, PlatformFactory.detect("Mac OS X", "arm64", ENV));

        Platform mac = PlatformFactory.detect("Darwin", "x86_64", ENV);
        assertEquals(OperatingSystem.MACOS, mac.os());
        assertEquals(Architecture.X64, mac.arch());
        assertSame(ENV, mac.environment());
    }

    @Test
    void unknownOperatingSystemIsRefusedWithAClearMessage() {
        UnsupportedPlatformException e = assertThrows(UnsupportedPlatformException.class,
                () -> PlatformFactory.detect("SunOS", "amd64", ENV));
        assertTrue(e.getMessage().contains("SunOS"), e.getMessage());
        assertTrue(e.getMessage().contains("amd64"), e.getMessage());
        assertTrue(e.getMessage().contains("Windows, Linux and macOS"), e.getMessage());
    }

    @Test
    void unknownArchitectureIsRefusedToo() {
        UnsupportedPlatformException e = assertThrows(UnsupportedPlatformException.class,
                () -> PlatformFactory.detect("Windows 10", "x86", ENV));
        assertTrue(e.getMessage().contains("x86"), e.getMessage());
    }

    @Test
    void currentPlatformIsDetectedOnceAndMatchesTheBuildMachine() {
        Platform current = PlatformFactory.current();
        assertSame(current, PlatformFactory.current());
        assertEquals(OperatingSystem.fromOsName(System.getProperty("os.name")), current.os());
        assertEquals(Architecture.fromOsArch(System.getProperty("os.arch")), current.arch());
    }
}
