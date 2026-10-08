package de.yawi.installer.core.download;

import de.yawi.installer.core.manifest.Provider;

/** The three ways a source can be obtained; the user's choice on the source page is one of these. */
public enum ProviderKind {
    BUNDLED, HTTP, TORRENT;

    public static ProviderKind of(Provider provider) {
        return switch (provider) {
            case Provider.Bundled b -> BUNDLED;
            case Provider.Http h -> HTTP;
            case Provider.Torrent t -> TORRENT;
        };
    }

    public boolean isSupported() {
        return true;
    }
}
