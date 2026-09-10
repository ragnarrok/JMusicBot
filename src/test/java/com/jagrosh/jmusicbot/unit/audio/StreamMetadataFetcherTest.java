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
package com.jagrosh.jmusicbot.unit.audio;

import com.jagrosh.jmusicbot.audio.StreamMetadata;
import com.jagrosh.jmusicbot.audio.StreamMetadataFetcher;
import com.jagrosh.jmusicbot.audio.StreamMetadataFetcher.Endpoint;
import com.jagrosh.jmusicbot.audio.StreamMetadataFetcher.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StreamMetadataFetcher Tests")
class StreamMetadataFetcherTest
{
    @Nested
    @DisplayName("Endpoint resolution")
    class EndpointResolution
    {
        @Test
        @DisplayName("Azuracast listen URL maps to its now-playing API")
        void azuracast()
        {
            Endpoint ep = StreamMetadataFetcher.resolveEndpoint("https://radio.example.com/listen/my_station/radio.mp3");
            assertNotNull(ep);
            assertEquals(Kind.AZURACAST, ep.kind());
            assertEquals("https://radio.example.com/api/nowplaying/my_station", ep.url());
        }

        @Test
        @DisplayName("port is preserved")
        void azuracastWithPort()
        {
            Endpoint ep = StreamMetadataFetcher.resolveEndpoint("http://10.0.0.5:8080/listen/rock/stream");
            assertEquals("http://10.0.0.5:8080/api/nowplaying/rock", ep.url());
        }

        @Test
        @DisplayName("other http URLs map to Icecast status on the same host")
        void icecast()
        {
            Endpoint ep = StreamMetadataFetcher.resolveEndpoint("http://icecast.example.org:8000/live.mp3?x=1");
            assertNotNull(ep);
            assertEquals(Kind.ICECAST, ep.kind());
            assertEquals("http://icecast.example.org:8000/status-json.xsl", ep.url());
        }

        @Test
        @DisplayName("non-http URLs and garbage resolve to nothing")
        void unsupported()
        {
            assertNull(StreamMetadataFetcher.resolveEndpoint("file:///music/live.mp3"));
            assertNull(StreamMetadataFetcher.resolveEndpoint("rtmp://host/app/stream"));
            assertNull(StreamMetadataFetcher.resolveEndpoint("not a url at all"));
            assertNull(StreamMetadataFetcher.resolveEndpoint(null));
        }
    }

    @Nested
    @DisplayName("Azuracast parsing")
    class AzuracastParsing
    {
        private static final String RESPONSE = """
            {
              "station": { "id": 1, "name": "Ragnarrok FM", "shortcode": "my_station" },
              "listeners": { "total": 12, "unique": 10, "current": 11 },
              "now_playing": {
                "song": { "text": "Daft Punk - Around the World", "artist": "Daft Punk", "title": "Around the World",
                          "art": "https://radio.example.com/api/station/1/art/abc.jpg" }
              }
            }
            """;

        @Test
        @DisplayName("reads station, song, art and listeners")
        void parsesFullResponse()
        {
            StreamMetadata md = StreamMetadataFetcher.parseAzuracast(RESPONSE).orElseThrow();
            assertEquals("Ragnarrok FM", md.stationName());
            assertEquals("Around the World", md.title());
            assertEquals("Daft Punk", md.artist());
            assertEquals("https://radio.example.com/api/station/1/art/abc.jpg", md.artworkUrl());
            assertEquals(11, md.listeners());
            assertEquals("Daft Punk - Around the World", md.songLine());
            assertNotNull(md.fetchedAt());
        }

        @Test
        @DisplayName("accepts the station-list array form and uses its first entry")
        void parsesArrayForm()
        {
            StreamMetadata md = StreamMetadataFetcher.parseAzuracast("[" + RESPONSE + "]").orElseThrow();
            assertEquals("Ragnarrok FM", md.stationName());
        }

        @Test
        @DisplayName("falls back to total listeners and tolerates missing fields")
        void partialResponse()
        {
            StreamMetadata md = StreamMetadataFetcher.parseAzuracast(
                    "{\"station\":{\"name\":\"X\"},\"listeners\":{\"total\":3},\"now_playing\":{\"song\":{\"title\":\"\"}}}").orElseThrow();
            assertEquals("X", md.stationName());
            assertNull(md.title());
            assertNull(md.songLine());
            assertEquals(3, md.listeners());
        }

        @Test
        @DisplayName("empty, malformed or unusable JSON yields nothing")
        void unusable()
        {
            assertEquals(Optional.empty(), StreamMetadataFetcher.parseAzuracast(""));
            assertEquals(Optional.empty(), StreamMetadataFetcher.parseAzuracast("{ nope"));
            assertEquals(Optional.empty(), StreamMetadataFetcher.parseAzuracast("{}"));
            assertEquals(Optional.empty(), StreamMetadataFetcher.parseAzuracast("[]"));
        }
    }

    @Nested
    @DisplayName("Icecast parsing")
    class IcecastParsing
    {
        private static final String MULTI = """
            { "icestats": { "source": [
                { "listenurl": "http://icecast.example.org:8000/talk", "server_name": "Talk", "title": "News hour", "listeners": 4 },
                { "listenurl": "http://icecast.example.org:8000/live.mp3", "server_name": "Live FM",
                  "title": "Massive Attack - Teardrop", "listeners": 27 }
            ] } }
            """;

        @Test
        @DisplayName("matches the source by mount and splits Artist - Title")
        void matchesMount()
        {
            StreamMetadata md = StreamMetadataFetcher.parseIcecast(MULTI, "http://icecast.example.org:8000/live.mp3").orElseThrow();
            assertEquals("Live FM", md.stationName());
            assertEquals("Massive Attack", md.artist());
            assertEquals("Teardrop", md.title());
            assertEquals(27, md.listeners());
            assertNull(md.artworkUrl());
        }

        @Test
        @DisplayName("mount matching is case-insensitive and ignores the host")
        void mountMatchIgnoresHost()
        {
            StreamMetadata md = StreamMetadataFetcher.parseIcecast(MULTI, "https://cdn.example.net/LIVE.MP3").orElseThrow();
            assertEquals("Live FM", md.stationName());
        }

        @Test
        @DisplayName("single source object is used even when the mount does not match")
        void singleSourceFallback()
        {
            String single = "{\"icestats\":{\"source\":{\"listenurl\":\"http://h/other\",\"server_name\":\"Solo\",\"title\":\"Just a title\",\"artist\":\"Given Artist\"}}}";
            StreamMetadata md = StreamMetadataFetcher.parseIcecast(single, "http://h/mount").orElseThrow();
            assertEquals("Solo", md.stationName());
            assertEquals("Given Artist", md.artist(), "explicit artist field wins over splitting");
            assertEquals("Just a title", md.title());
            assertEquals(-1, md.listeners());
        }

        @Test
        @DisplayName("several sources with no matching mount yields nothing")
        void noMatchAmongMany()
        {
            assertEquals(Optional.empty(), StreamMetadataFetcher.parseIcecast(MULTI, "http://icecast.example.org:8000/unknown"));
        }

        @Test
        @DisplayName("no sources or malformed JSON yields nothing")
        void unusable()
        {
            assertEquals(Optional.empty(), StreamMetadataFetcher.parseIcecast("{\"icestats\":{}}", "http://h/m"));
            assertEquals(Optional.empty(), StreamMetadataFetcher.parseIcecast("<html>", "http://h/m"));
        }
    }

    @Test
    @DisplayName("sameSong ignores listener count and fetch time")
    void sameSongIgnoresVolatileFields()
    {
        StreamMetadata a = new StreamMetadata("S", "T", "A", null, 1, java.time.Instant.EPOCH);
        StreamMetadata b = new StreamMetadata("S", "T", "A", null, 99, java.time.Instant.now());
        StreamMetadata c = new StreamMetadata("S", "T2", "A", null, 1, java.time.Instant.EPOCH);
        assertTrue(a.sameSong(b));
        assertFalse(a.sameSong(c));
        assertFalse(a.sameSong(null));
    }
}
