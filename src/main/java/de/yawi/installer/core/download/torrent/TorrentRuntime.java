package de.yawi.installer.core.download.torrent;

import bt.runtime.BtRuntime;
import bt.runtime.Config;
import bt.service.ApplicationService;
import bt.service.Version;
import bt.tracker.http.HttpTrackerModule;
import com.google.inject.Binder;
import com.google.inject.Module;
import de.yawi.installer.core.manifest.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Holder of the process's {@code BtRuntime} (E08-S02): at most one exists at
 * a time. It is built lazily from the torrent provider that needs it and
 * disposed of after every download (see {@code TorrentDownloader}); whatever
 * is left is shut down by the wizard when it closes, by the silent run when
 * it ends, and by a JVM shutdown hook as the last resort.
 *
 * <p>The listen port is the provider's, or a free one if that is busy.
 */
public final class TorrentRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(TorrentRuntime.class);
    private static final Duration SHUTDOWN_HOOK_TIMEOUT = Duration.ofSeconds(5);

    private static TorrentRuntime shared;

    private final Consumer<Config> customizer;
    private final List<Module> extraModules;
    private BtRuntime runtime;
    private int port = -1;
    private Thread hook;

    /**
     * @param customizer   adjusts the configuration before the runtime is built (tests: loopback, small blocks)
     * @param extraModules Guice modules added to the standard ones (tests: fixed peers)
     */
    public TorrentRuntime(Consumer<Config> customizer, List<Module> extraModules) {
        this.customizer = Objects.requireNonNull(customizer, "customizer");
        this.extraModules = List.copyOf(extraModules);
    }

    public TorrentRuntime() {
        this(config -> { }, List.of());
    }

    /** The process-wide instance. */
    public static synchronized TorrentRuntime shared() {
        if (shared == null) {
            shared = new TorrentRuntime();
        }
        return shared;
    }

    /** Stops the process-wide instance if it was ever started; safe to call any time. */
    public static synchronized void shutdownShared() {
        if (shared != null) {
            shared.shutdown();
        }
    }

    /** The runtime, built on first use with this provider's port and peer-source switches. */
    public synchronized BtRuntime runtime(Provider.Torrent provider) {
        if (runtime == null) {
            Config config = new Config();
            port = choosePort(provider.port());
            config.setAcceptorPort(port);
            config.setShutdownHookTimeout(SHUTDOWN_HOOK_TIMEOUT);
            customizer.accept(config);
            var builder = BtRuntime.builder(config)
                    .disableAutomaticShutdown()
                    .module(new HttpTrackerModule())
                    .module(new InstallerIdentityModule())
                    .module(new ClosingTrackerService.TrackerModule());
            if (!provider.lsd()) {
                builder.disableLocalServiceDiscovery();
            }
            if (!provider.pex()) {
                builder.disablePeerExchange();
            }
            extraModules.forEach(builder::module);
            runtime = builder.build();
            hook = new Thread(this::shutdown, "yawi-installer-torrent-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            LOG.info("BitTorrent runtime ready on port {} (lsd={}, pex={})", port, provider.lsd(), provider.pex());
        } else if (provider.port() != port) {
            LOG.debug("BitTorrent runtime already on port {}, provider asked for {}", port, provider.port());
        }
        return runtime;
    }

    /** The listen port in use, or -1 before the first download. */
    public synchronized int port() {
        return port;
    }

    public synchronized boolean isStarted() {
        return runtime != null && runtime.isRunning();
    }

    /** Stops every client and the runtime; a later {@link #runtime} builds a fresh one. Idempotent. */
    public synchronized void shutdown() {
        if (runtime == null) {
            return;
        }
        BtRuntime rt = runtime;
        runtime = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // the JVM is already shutting down - the hook is what called us
            }
            hook = null;
        }
        if (rt.isRunning()) {
            LOG.info("Shutting down BitTorrent runtime on port {}", port);
            rt.shutdown();
        }
    }

    /**
     * {@code wanted} if it can be bound, else a free port the OS hands out
     * ({@code 0} = any free port); the small window between probing and
     * bt's own bind is accepted.
     */
    public static int choosePort(int wanted) {
        if (wanted > 0 && canBind(wanted)) {
            return wanted;
        }
        try (ServerSocket socket = new ServerSocket(0, 1, (InetAddress) null)) {
            int free = socket.getLocalPort();
            if (wanted > 0) {
                LOG.warn("BitTorrent port {} is busy, using {} instead", wanted, free);
            }
            return free;
        } catch (IOException e) {
            throw new IllegalStateException("no free port for BitTorrent", e);
        }
    }

    private static boolean canBind(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress((InetAddress) null, port), 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * bt reads its version from the jar manifest to build the peer id and
     * warns when it cannot; the installer's build has none of that, so the
     * vendored version is stated here.
     */
    static final class InstallerIdentityModule implements Module {
        @Override
        public void configure(Binder binder) {
            binder.bind(ApplicationService.class).toInstance(() -> new Version(1, 10, false));
        }
    }
}
