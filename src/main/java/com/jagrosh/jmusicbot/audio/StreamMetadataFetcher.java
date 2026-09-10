/*
 * Copyright 2026 Cohen Singh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Looks up what a radio station is currently playing.
 * <p>
 * Two server types are understood, chosen from the shape of the stream URL:
 * <ul>
 *   <li><b>Azuracast</b>: stream URLs look like {@code https://host/listen/<station>/<mount>};
 *       the song comes from {@code https://host/api/nowplaying/<station>}.</li>
 *   <li><b>Icecast / Shoutcast-style</b>: anything else on http(s); the song comes from
 *       {@code https://host[:port]/status-json.xsl}, matching the source whose listen URL ends
 *       with the stream's mount path.</li>
 * </ul>
 * Parsing is separated from fetching so it can be unit tested without a network.
 *
 * @author Cohen Singh
 */
public class StreamMetadataFetcher
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamMetadataFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int TIMEOUT_SECONDS = 5;

    public enum Kind { AZURACAST, ICECAST }

    /** Where to ask, and how to read the answer. */
    public record Endpoint(Kind kind, String url) {}

    private final OkHttpClient client;

    public StreamMetadataFetcher(Proxy proxy)
    {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (proxy != null)
            builder.proxy(proxy);
        this.client = builder.build();
    }

    /**
     * Fetches the current song for a stream URL. Empty when the URL is not something we know how
     * to query, the server does not answer, or the answer holds nothing usable.
     */
    public Optional<StreamMetadata> fetch(String streamUri)
    {
        Endpoint endpoint = resolveEndpoint(streamUri);
        if (endpoint == null)
            return Optional.empty();
        String body = get(endpoint.url());
        if (body == null)
            return Optional.empty();
        return endpoint.kind() == Kind.AZURACAST
                ? parseAzuracast(body)
                : parseIcecast(body, streamUri);
    }

    private String get(String url)
    {
        Request request = new Request.Builder().get().url(url)
                .header("User-Agent", "JMusicBot (stream metadata)")
                .header("Accept", "application/json")
                .build();
        try (Response response = client.newCall(request).execute())
        {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null)
            {
                LOG.debug("Metadata request to {} returned HTTP {}", url, response.code());
                return null;
            }
            return body.string();
        }
        catch (IOException | RuntimeException e)
        {
            LOG.debug("Metadata request to {} failed: {}", url, e.toString());
            return null;
        }
    }

    /**
     * Works out which API to query for a stream URL, or null if the URL is not http(s).
     */
    public static Endpoint resolveEndpoint(String streamUri)
    {
        if (streamUri == null)
            return null;
        URI uri;
        try
        {
            uri = URI.create(streamUri.trim());
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null)
            return null;
        scheme = scheme.toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https"))
            return null;

        String base = scheme + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        List<String> segments = pathSegments(uri.getPath());
        if (segments.size() >= 2 && segments.get(0).equalsIgnoreCase("listen"))
            return new Endpoint(Kind.AZURACAST, base + "/api/nowplaying/" + segments.get(1));
        return new Endpoint(Kind.ICECAST, base + "/status-json.xsl");
    }

    /**
     * Reads an Azuracast {@code /api/nowplaying/<station>} response. A bare station-list array
     * (from {@code /api/nowplaying}) is accepted too; its first entry is used.
     */
    public static Optional<StreamMetadata> parseAzuracast(String json)
    {
        JsonNode root = readTree(json);
        if (root == null)
            return Optional.empty();
        if (root.isArray())
            root = root.size() > 0 ? root.get(0) : null;
        if (root == null || !root.isObject())
            return Optional.empty();

        JsonNode song = root.path("now_playing").path("song");
        String stationName = text(root.path("station").path("name"));
        String title = text(song.path("title"));
        String artist = text(song.path("artist"));
        String art = text(song.path("art"));
        JsonNode listenersNode = root.path("listeners");
        int listeners = listenersNode.path("current").isNumber()
                ? listenersNode.path("current").asInt()
                : listenersNode.path("total").isNumber() ? listenersNode.path("total").asInt() : -1;

        if (stationName == null && title == null && artist == null)
            return Optional.empty();
        return Optional.of(new StreamMetadata(stationName, title, artist, art, listeners, Instant.now()));
    }

    /**
     * Reads an Icecast {@code /status-json.xsl} response, picking the source that serves the
     * given stream URL (by mount path). Falls back to the only source when there is just one.
     */
    public static Optional<StreamMetadata> parseIcecast(String json, String streamUri)
    {
        JsonNode root = readTree(json);
        if (root == null)
            return Optional.empty();
        JsonNode sourceNode = root.path("icestats").path("source");
        List<JsonNode> sources = new ArrayList<>();
        if (sourceNode.isArray())
            sourceNode.forEach(sources::add);
        else if (sourceNode.isObject())
            sources.add(sourceNode);
        if (sources.isEmpty())
            return Optional.empty();

        String mount = mountOf(streamUri);
        JsonNode match = null;
        for (JsonNode source : sources)
        {
            String listenUrl = text(source.path("listenurl"));
            if (mount != null && listenUrl != null && mount.equals(mountOf(listenUrl)))
            {
                match = source;
                break;
            }
        }
        if (match == null)
        {
            if (sources.size() != 1)
                return Optional.empty();
            match = sources.get(0);
        }

        String stationName = text(match.path("server_name"));
        String artist = text(match.path("artist"));
        String title = text(match.path("title"));
        if (artist == null && title != null)
        {
            // Icecast usually reports "Artist - Title" as one string.
            int sep = title.indexOf(" - ");
            if (sep > 0)
            {
                artist = title.substring(0, sep).trim();
                title = title.substring(sep + 3).trim();
            }
        }
        int listeners = match.path("listeners").isNumber() ? match.path("listeners").asInt() : -1;

        if (stationName == null && title == null && artist == null)
            return Optional.empty();
        return Optional.of(new StreamMetadata(stationName, title, artist, null, listeners, Instant.now()));
    }

    /** Last path segment of a URL (the Icecast mount), or null. */
    static String mountOf(String url)
    {
        try
        {
            List<String> segments = pathSegments(URI.create(url.trim()).getPath());
            return segments.isEmpty() ? null : segments.get(segments.size() - 1).toLowerCase();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static List<String> pathSegments(String path)
    {
        List<String> segments = new ArrayList<>();
        if (path == null)
            return segments;
        for (String part : path.split("/"))
            if (!part.isEmpty())
                segments.add(part);
        return segments;
    }

    private static JsonNode readTree(String json)
    {
        if (json == null || json.isBlank())
            return null;
        try
        {
            return MAPPER.readTree(json);
        }
        catch (IOException | RuntimeException e)
        {
            LOG.debug("Could not parse station metadata: {}", e.toString());
            return null;
        }
    }

    private static String text(JsonNode node)
    {
        if (node == null || node.isMissingNode() || node.isNull())
            return null;
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
