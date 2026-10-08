package de.yawi.installer.core.download.torrent;

import bt.module.TrackerFactories;
import bt.service.IRuntimeLifecycleBinder;
import bt.tracker.AnnounceKey;
import bt.tracker.ITrackerService;
import bt.tracker.Tracker;
import bt.tracker.TrackerFactory;
import bt.tracker.TrackerService;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * bt's {@code TrackerService} never closes the trackers it created, so every
 * runtime leaves a UDP message-worker thread behind. This one remembers
 * them and closes them on the runtime's shutdown (E08-S02-T03) - bound in
 * place of the original by {@link ClosingTrackerService.TrackerModule}, no
 * patch to bt needed.
 */
final class ClosingTrackerService implements ITrackerService {

    private static final Logger LOG = LoggerFactory.getLogger(ClosingTrackerService.class);

    private final TrackerService delegate;
    private final Set<Tracker> trackers = ConcurrentHashMap.newKeySet();

    @Inject
    ClosingTrackerService(@TrackerFactories Map<String, TrackerFactory> trackerFactories,
                          IRuntimeLifecycleBinder lifecycle) {
        this.delegate = new TrackerService(trackerFactories);
        lifecycle.onShutdown("Close trackers", this::closeAll);
    }

    @Override
    public boolean isSupportedProtocol(String trackerUrl) {
        return delegate.isSupportedProtocol(trackerUrl);
    }

    @Override
    public Tracker getTracker(String trackerUrl) {
        return remember(delegate.getTracker(trackerUrl));
    }

    @Override
    public Tracker getTracker(AnnounceKey announceKey) {
        return remember(delegate.getTracker(announceKey));
    }

    private Tracker remember(Tracker tracker) {
        trackers.add(tracker);
        return tracker;
    }

    private void closeAll() {
        for (Tracker tracker : trackers) {
            try {
                tracker.close();
            } catch (IOException | RuntimeException e) {
                LOG.debug("Closing tracker {} failed: {}", tracker, e.toString());
            }
        }
        trackers.clear();
    }

    /** Overrides bt's {@code ITrackerService} binding. */
    static final class TrackerModule implements Module {
        @Override
        public void configure(Binder binder) {
            binder.bind(ITrackerService.class).to(ClosingTrackerService.class).in(Singleton.class);
        }
    }
}
