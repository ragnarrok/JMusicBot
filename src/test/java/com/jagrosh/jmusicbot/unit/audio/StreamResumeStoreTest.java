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

import com.jagrosh.jmusicbot.audio.StreamResumeStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StreamResumeStore Tests")
class StreamResumeStoreTest
{
    private static final String URI = "https://radio.example.com/listen/station/radio.mp3";

    @TempDir
    Path dir;

    private Path file()
    {
        return dir.resolve("streamresume.json");
    }

    @Test
    @DisplayName("record() writes the file and a new store reads it back")
    void recordPersistsAndReloads() throws Exception
    {
        StreamResumeStore store = new StreamResumeStore(true, file());
        store.record(1L, 42L, URI, "Station");

        assertTrue(Files.exists(file()));
        String json = Files.readString(file());
        assertTrue(json.contains("\"guild_id\" : 1"));
        assertTrue(json.contains(URI));

        StreamResumeStore reloaded = new StreamResumeStore(true, file());
        StreamResumeStore.Entry entry = reloaded.get(1L);
        assertNotNull(entry);
        assertEquals(42L, entry.voiceChannelId());
        assertEquals(URI, entry.uri());
        assertEquals("Station", entry.title());
        assertEquals(1, reloaded.entries().size());
    }

    @Test
    @DisplayName("record() replaces a guild's previous entry")
    void recordReplaces()
    {
        StreamResumeStore store = new StreamResumeStore(true, file());
        store.record(1L, 42L, URI, "Station");
        store.record(1L, 43L, "https://other.example/stream", "Other");

        assertEquals(1, store.entries().size());
        assertEquals(43L, store.get(1L).voiceChannelId());
        assertEquals("https://other.example/stream", new StreamResumeStore(true, file()).get(1L).uri());
    }

    @Test
    @DisplayName("clear() removes the entry and updates the file")
    void clearRemoves()
    {
        StreamResumeStore store = new StreamResumeStore(true, file());
        store.record(1L, 42L, URI, "Station");
        store.record(2L, 7L, URI, "Station");

        store.clear(1L);

        assertNull(store.get(1L));
        assertNotNull(store.get(2L));
        assertNull(new StreamResumeStore(true, file()).get(1L));
    }

    @Test
    @DisplayName("disabled store never touches the file system")
    void disabledNeverWrites()
    {
        StreamResumeStore store = new StreamResumeStore(false, file());
        store.record(1L, 42L, URI, "Station");
        store.clear(1L);

        assertFalse(store.isEnabled());
        assertFalse(Files.exists(file()));
        assertTrue(store.entries().isEmpty());
    }

    @Test
    @DisplayName("frozen store ignores record() and clear()")
    void frozenIgnoresWrites()
    {
        StreamResumeStore store = new StreamResumeStore(true, file());
        store.record(1L, 42L, URI, "Station");

        store.freeze();
        store.clear(1L);
        store.record(2L, 1L, URI, "Station");

        assertTrue(store.isFrozen());
        assertNotNull(store.get(1L));
        assertNull(store.get(2L));
        assertNotNull(new StreamResumeStore(true, file()).get(1L), "file still holds the entry");
    }

    @Test
    @DisplayName("blank URI is ignored")
    void blankUriIgnored()
    {
        StreamResumeStore store = new StreamResumeStore(true, file());
        store.record(1L, 42L, " ", "Station");
        store.record(1L, 42L, null, "Station");

        assertTrue(store.entries().isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    @DisplayName("malformed or unexpected file content is ignored")
    void malformedFileIgnored() throws Exception
    {
        Files.writeString(file(), "{ not json");
        assertTrue(new StreamResumeStore(true, file()).entries().isEmpty());

        Files.writeString(file(), "{\"guild_id\": 1}");
        assertTrue(new StreamResumeStore(true, file()).entries().isEmpty(), "object instead of array");

        Files.writeString(file(), "[{\"voice_channel_id\": 5}, {\"guild_id\": 3, \"uri\": \"" + URI + "\"}]");
        StreamResumeStore store = new StreamResumeStore(true, file());
        assertEquals(1, store.entries().size(), "entries missing guild_id or uri are skipped");
        assertEquals(0L, store.get(3L).voiceChannelId());
        assertNull(store.get(3L).title());
    }
}
