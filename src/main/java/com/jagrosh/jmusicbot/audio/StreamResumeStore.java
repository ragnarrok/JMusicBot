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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which live stream each guild was playing, and in which voice channel, so the
 * bot can pick it up again after a restart.
 * <p>
 * The store is written whenever a live stream starts and cleared when playback stops or a
 * non-stream track takes over. During shutdown the store is {@link #freeze() frozen} so the
 * stop-and-clear that happens on the way out does not erase what should be resumed.
 * <p>
 * When disabled the store never touches the file system.
 *
 * @author Cohen Singh
 */
public class StreamResumeStore
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamResumeStore.class);
    public static final String FILE_NAME = "streamresume.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What to resume for one guild. */
    public record Entry(long guildId, long voiceChannelId, String uri, String title) {}

    private final boolean enabled;
    private final Path file;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean frozen;

    /** Creates a store backed by {@code streamresume.json} next to the other bot files. */
    public StreamResumeStore(boolean enabled)
    {
        this(enabled, OtherUtil.getPath(FILE_NAME));
    }

    public StreamResumeStore(boolean enabled, Path file)
    {
        this.enabled = enabled;
        this.file = file;
        if (enabled)
            load();
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    /**
     * Records the stream a guild is now playing. No-op when disabled or frozen, or when the
     * same stream and channel are already recorded.
     */
    public void record(long guildId, long voiceChannelId, String uri, String title)
    {
        if (!enabled || frozen || uri == null || uri.isBlank())
            return;
        Entry entry = new Entry(guildId, voiceChannelId, uri, title);
        Entry previous = entries.put(guildId, entry);
        if (!entry.equals(previous))
            save();
    }

    /** Forgets the stream for a guild. No-op when disabled or frozen. */
    public void clear(long guildId)
    {
        if (!enabled || frozen)
            return;
        if (entries.remove(guildId) != null)
            save();
    }

    public Entry get(long guildId)
    {
        return entries.get(guildId);
    }

    public Collection<Entry> entries()
    {
        return List.copyOf(entries.values());
    }

    /**
     * Stops all further writes. Called at the start of shutdown so the streams that are being
     * stopped on the way out are still there when the bot comes back.
     */
    public void freeze()
    {
        frozen = true;
    }

    public boolean isFrozen()
    {
        return frozen;
    }

    private void load()
    {
        try
        {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            if (root == null || !root.isArray())
                return;
            for (JsonNode node : root)
            {
                if (!node.hasNonNull("guild_id") || !node.hasNonNull("uri"))
                    continue;
                long guildId = node.get("guild_id").asLong();
                entries.put(guildId, new Entry(
                        guildId,
                        node.path("voice_channel_id").asLong(0L),
                        node.get("uri").asText(),
                        node.path("title").asText(null)));
            }
            if (!entries.isEmpty())
                LOG.info("Loaded {} stream(s) to resume from {}", entries.size(), file.toAbsolutePath());
        }
        catch (NoSuchFileException e)
        {
            // Nothing to resume yet.
        }
        catch (IOException | RuntimeException e)
        {
            LOG.warn("Failed to read {}: {}", file.toAbsolutePath(), e.toString());
        }
    }

    private synchronized void save()
    {
        try
        {
            ArrayNode root = MAPPER.createArrayNode();
            for (Entry entry : entries.values())
            {
                ObjectNode node = root.addObject();
                node.put("guild_id", entry.guildId());
                node.put("voice_channel_id", entry.voiceChannelId());
                node.put("uri", entry.uri());
                if (entry.title() != null)
                    node.put("title", entry.title());
            }
            Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
        catch (IOException e)
        {
            LOG.warn("Failed to write {}: {}", file.toAbsolutePath(), e.toString());
        }
    }
}
