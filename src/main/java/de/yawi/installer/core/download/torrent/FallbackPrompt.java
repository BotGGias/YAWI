package de.yawi.installer.core.download.torrent;

import de.yawi.installer.core.manifest.Provider;
import de.yawi.installer.core.manifest.Source;

/**
 * Asked when a torrent provider with {@code fallback="ask"} found no peers
 * and another provider could take over (E08-S03). Called on the downloading
 * thread; an implementation that shows a dialog blocks until it is answered.
 */
@FunctionalInterface
public interface FallbackPrompt {

    enum Decision { FALLBACK, ABORT }

    /** Never asks: the next provider is tried, as with {@code fallback="auto"}. */
    FallbackPrompt ALWAYS = (source, torrent, cause) -> Decision.FALLBACK;

    Decision ask(Source source, Provider.Torrent torrent, NoPeersException cause);
}
