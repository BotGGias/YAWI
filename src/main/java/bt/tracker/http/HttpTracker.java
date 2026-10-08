/*
 * Copyright (c) 2016—2021 Andrei Tomashpolskiy and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bt.tracker.http;

import bt.BtException;
import bt.metainfo.TorrentId;
import bt.net.Peer;
import bt.peer.IPeerRegistry;
import bt.protocol.crypto.EncryptionPolicy;
import bt.service.IdentityService;
import bt.torrent.TorrentDescriptor;
import bt.torrent.TorrentRegistry;
import bt.torrent.TorrentSessionState;
import bt.tracker.SecretKey;
import bt.tracker.Tracker;
import bt.tracker.TrackerRequestBuilder;
import bt.tracker.TrackerResponse;
import bt.tracker.http.urlencoding.TrackerQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Simple implementation of an HTTP tracker client.
 *
 * <p>YAWI patch P02: the transport is the JDK's {@link HttpClient}
 * instead of Apache HttpComponents (see docs/backlog/bt-patches.md). The
 * announce protocol is unchanged.
 *
 * @since 1.0
 */
public class HttpTracker implements Tracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpTracker.class);

    protected enum TrackerRequestType {
        START("started"),
        STOP("stopped"),
        COMPLETE("completed"),
        QUERY(null);
        private final String eventVal;

        TrackerRequestType(String eventVal) {
            this.eventVal = eventVal;
        }

        public String getEventVal() {
            return eventVal;
        }
    }

    private final URI baseUri;
    private final Duration timeout;
    private final TorrentRegistry torrentRegistry;
    private final IdentityService idService;
    private final IPeerRegistry peerRegistry;
    private final EncryptionPolicy encryptionPolicy;
    private final int numberOfPeersToRequestFromTracker;
    private final HttpClient httpClient;
    private final HttpResponseHandler httpResponseHandler;

    private final ConcurrentMap<URI, byte[]> trackerIds;

    /**
     * @param trackerUrl Tracker URL
     * @param idService  Identity service
     * @since 1.0
     */
    public HttpTracker(String trackerUrl,
                       TorrentRegistry torrentRegistry,
                       IdentityService idService,
                       IPeerRegistry peerRegistry,
                       EncryptionPolicy encryptionPolicy,
                       InetAddress localAddress,
                       int numberOfPeersToRequestFromTracker,
                       Duration timeout) {
        try {
            this.baseUri = new URI(trackerUrl);
        } catch (URISyntaxException e) {
            throw new BtException("Invalid URL: " + trackerUrl, e);
        }

        this.torrentRegistry = torrentRegistry;
        this.idService = idService;
        this.peerRegistry = peerRegistry;
        this.encryptionPolicy = encryptionPolicy;
        this.numberOfPeersToRequestFromTracker = numberOfPeersToRequestFromTracker;
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (localAddress != null && !localAddress.isAnyLocalAddress()) {
            builder.localAddress(localAddress);
        }
        this.httpClient = builder.build();
        this.httpResponseHandler = new HttpResponseHandler();

        this.trackerIds = new ConcurrentHashMap<>();
    }

    @Override
    public TrackerRequestBuilder request(TorrentId torrentId) {
        return new TrackerRequestBuilder(torrentId) {
            @Override
            public TrackerResponse start() {
                return sendEvent(TrackerRequestType.START, this);
            }

            @Override
            public TrackerResponse stop() {
                return sendEvent(TrackerRequestType.STOP, this);
            }

            @Override
            public TrackerResponse complete() {
                return sendEvent(TrackerRequestType.COMPLETE, this);
            }

            @Override
            public TrackerResponse query() {
                return sendEvent(TrackerRequestType.QUERY, this);
            }
        };
    }

    private TrackerResponse sendEvent(TrackerRequestType eventType, TrackerRequestBuilder requestBuilder) {
        String requestUri = buildQueryUri(eventType, requestBuilder);

        // "Connection: close" is a restricted header for the JDK client; its idle
        // connections expire on their own (jdk.httpclient.keepalive.timeout).
        HttpRequest request = HttpRequest.newBuilder(URI.create(requestUri))
                .timeout(timeout)
                .GET()
                .build();
        try {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Executing tracker HTTP request of type " + eventType.name() +
                        "; request URL: " + requestUri);
            }
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return handleResponse(response);
        } catch (IOException e) {
            return TrackerResponse.exceptional(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TrackerResponse.exceptional(new BtException("Interrupted while querying tracker", e));
        }
    }

    private TrackerResponse handleResponse(HttpResponse<byte[]> response) {
        if (response.statusCode() >= 300) {
            return TrackerResponse.exceptional(new BtException(
                    "Tracker returned error (" + response.statusCode() + ")"));
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            return TrackerResponse.exceptional(new BtException("Tracker response is empty"));
        }
        return httpResponseHandler.handleResponse(body, charsetOf(response));
    }

    /** The charset of the Content-Type header, else ISO-8859-1 as HTTP's historical default. */
    private static Charset charsetOf(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").flatMap(contentType -> {
            for (String part : contentType.split(";")) {
                String trimmed = part.trim().toLowerCase(Locale.ROOT);
                if (trimmed.startsWith("charset=")) {
                    try {
                        return Optional.of(Charset.forName(trimmed.substring("charset=".length()).replace("\"", "")));
                    } catch (IllegalArgumentException e) {
                        LOGGER.debug("Unknown charset in tracker response: {}", contentType);
                    }
                }
            }
            return Optional.empty();
        }).orElse(StandardCharsets.ISO_8859_1);
    }

    private String buildQueryUri(TrackerRequestType eventType, TrackerRequestBuilder requestBuilder) {
        String requestUri;
        try {
            String query = buildQuery(eventType, requestBuilder);

            String baseUrl = baseUri.toASCIIString();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            URL requestUrl = new URL(baseUrl + (baseUri.getRawQuery() == null ? "?" : "&") + query);
            requestUri = requestUrl.toURI().toString();

        } catch (Exception e) {
            throw new BtException("Failed to build tracker request", e);
        }
        return requestUri;
    }

    private String buildQuery(TrackerRequestType eventType, TrackerRequestBuilder requestBuilder) {
        TrackerQueryBuilder queryBuilder = createTrackerQuery(eventType, requestBuilder);
        return queryBuilder.toQuery();
    }

    /**
     * Build the query to send to the tracker. This method is protected so that this class can easily be extended
     * to support additional tracker parameters, for example
     * <a href="https://wiki.theory.org/BitTorrent_Location-aware_Protocol_1.0_Specification">BitTorrent location aware protocol</a>
     *
     * @param eventType      The event type to announce to the tracker
     * @param requestBuilder the information to build the request
     */
    protected TrackerQueryBuilder createTrackerQuery(TrackerRequestType eventType,
                                                     TrackerRequestBuilder requestBuilder) {
        TrackerQueryBuilder queryBuilder = new TrackerQueryBuilder();

        queryBuilder.add("info_hash", requestBuilder.getTorrentId().getBytes());
        queryBuilder.add("peer_id", idService.getLocalPeerId().getBytes());

        Peer peer = peerRegistry.getLocalPeer();
        InetAddress inetAddress = peer.getInetAddress();
        if (inetAddress != null) {
            queryBuilder.add("ip", inetAddress.getHostAddress());
        }

        queryBuilder.add("port", peer.getPort());

        // set the torrent state if we can.
        torrentRegistry.getDescriptor(requestBuilder.getTorrentId())
                .flatMap(TorrentDescriptor::getSessionState)
                .ifPresent(state -> {
                    queryBuilder.add("uploaded", state.getUploaded());
                    queryBuilder.add("downloaded", state.getDownloaded());
                    long left = state.getLeft();
                    if (left != TorrentSessionState.UNKNOWN) {
                        queryBuilder.add("left", state.getLeft());
                    }
                });

        queryBuilder.add("compact", 1);
        int numWant =
                requestBuilder.getNumWant() == null ? numberOfPeersToRequestFromTracker : requestBuilder.getNumWant();
        queryBuilder.add("numwant", numWant);

        Optional<SecretKey> secretKey = idService.getSecretKey();
        if (secretKey.isPresent()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            secretKey.get().writeTo(bos);
            queryBuilder.add("key", bos.toByteArray());
        }

        byte[] trackerId = trackerIds.get(baseUri);
        if (trackerId != null) {
            queryBuilder.add("trackerid", trackerId);
        }

        if (null != eventType.getEventVal()) {
            queryBuilder.add("event", eventType.getEventVal());
        }

        switch (encryptionPolicy) {
            case PREFER_PLAINTEXT:
            case PREFER_ENCRYPTED:
                queryBuilder.add("supportcrypto", 1);
                break;
            case REQUIRE_ENCRYPTED: {
                queryBuilder.add("requirecrypto", 1);
                break;
            }
            default: {
                // do nothing
            }
        }
        return queryBuilder;
    }

    @Override
    public String toString() {
        return "HttpTracker{" + "baseUri=" + baseUri + '}';
    }

    @Override
    public void close() {
        // The JDK client has no resources to release beyond its idle connections.
    }
}
