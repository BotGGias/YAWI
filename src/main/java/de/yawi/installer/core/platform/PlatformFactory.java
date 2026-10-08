package de.yawi.installer.core.platform;

/**
 * Detects the running platform. The only place that reads {@code os.name}
 * and {@code os.arch}.
 */
public final class PlatformFactory {

    private static final class Holder {
        static final Platform CURRENT = detect(
                System.getProperty("os.name"), System.getProperty("os.arch"), Environment.system());
    }

    private PlatformFactory() {
    }

    /**
     * The platform this process runs on, detected once.
     *
     * @throws UnsupportedPlatformException on an unknown OS or architecture
     */
    public static Platform current() {
        try {
            return Holder.CURRENT;
        } catch (ExceptionInInitializerError e) {
            if (e.getCause() instanceof UnsupportedPlatformException unsupported) {
                throw unsupported;
            }
            throw e;
        }
    }

    /**
     * Builds the platform for the given identification; separated from the
     * system properties so that every spelling can be tested.
     *
     * @throws UnsupportedPlatformException on an unknown OS or architecture
     */
    public static Platform detect(String osName, String osArch, Environment environment) {
        OperatingSystem os = OperatingSystem.fromOsName(osName);
        Architecture arch = Architecture.fromOsArch(osArch);
        if (os == OperatingSystem.UNKNOWN || arch == Architecture.UNKNOWN) {
            throw new UnsupportedPlatformException(osName, osArch);
        }
        return switch (os) {
            case WINDOWS -> new WindowsPlatform(arch, environment);
            case LINUX -> new LinuxPlatform(arch, environment);
            case MACOS -> new MacPlatform(arch, environment);
            case UNKNOWN -> throw new UnsupportedPlatformException(osName, osArch);
        };
    }
}
