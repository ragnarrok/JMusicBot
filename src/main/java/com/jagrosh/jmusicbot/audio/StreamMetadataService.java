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

import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.utils.ProxyUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls the station behind each guild's live stream and reports when the song changes.
 * <p>
 * Polling runs on its own single daemon thread so a slow station cannot delay the bot's
 * shared scheduler. One poll per guild is active at a time; it starts when a live stream
 * starts and stops when the track ends.
 *
 * @author Cohen Singh
 */
public class StreamMetadataService
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamMetadataService.class);

    /** Receives song changes. Called on the polling thread. */
    @FunctionalInterface
    public interface Listener
    {
        void onMetadataChanged(long guildId, AudioTrack track, StreamMetadata metadata);
    }

    private static final class Poll
    {
        final AudioTrack track;
        volatile ScheduledFuture<?> future;
        volatile StreamMetadata current;

        Poll(AudioTrack track)
        {
            this.track = track;
        }
    }

    private final boolean enabled;
    private final long pollSeconds;
    private final StreamMetadataFetcher fetcher;
    private final ScheduledExecutorService executor;
    private final Map<Long, Poll> polls = new ConcurrentHashMap<>();

    public StreamMetadataService(boolean enabled, long pollSeconds, StreamMetadataFetcher fetcher, ScheduledExecutorService executor)
    {
        this.enabled = enabled;
        this.pollSeconds = Math.max(1L, pollSeconds);
        this.fetcher = fetcher;
        this.executor = executor;
    }

    public static StreamMetadataService fromConfig(BotConfig config)
    {
        Proxy proxy = config.proxyLavaplayer() && config.hasProxy() ? ProxyUtil.createProxy(config) : null;
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "stream-metadata");
            t.setDaemon(true);
            return t;
        });
        return new StreamMetadataService(config.showStreamMetadata(), config.getStreamMetadataPollSeconds(),
                new StreamMetadataFetcher(proxy), executor);
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    /**
     * Starts polling the station behind {@code track} for a guild, replacing any earlier poll.
     * Does nothing when disabled, when the track is not a live stream, or when its URL is not
     * something the fetcher knows how to query.
     */
    public void start(long guildId, AudioTrack track, Listener listener)
    {
        if (!enabled || !StreamReconnectPolicy.isLiveStream(track))
            return;
        String uri = track.getInfo().uri;
        if (StreamMetadataFetcher.resolveEndpoint(uri) == null)
            return;

        stop(guildId);
        Poll poll = new Poll(track);
        polls.put(guildId, poll);
        poll.future = executor.scheduleWithFixedDelay(() -> pollOnce(guildId, poll, listener), 0, pollSeconds, TimeUnit.SECONDS);
        LOG.debug("Polling station metadata for guild {} every {}s: {}", guildId, pollSeconds, uri);
    }

    private void pollOnce(long guildId, Poll poll, Listener listener)
    {
        try
        {
            if (polls.get(guildId) != poll)
                return; // superseded or stopped
            Optional<StreamMetadata> fetched = fetcher.fetch(poll.track.getInfo().uri);
            if (fetched.isEmpty())
                return;
            StreamMetadata metadata = fetched.get();
            StreamMetadata previous = poll.current;
            poll.current = metadata;
            if (!metadata.sameSong(previous))
            {
                LOG.debug("Station metadata changed for guild {}: {}", guildId, metadata.songLine());
                listener.onMetadataChanged(guildId, poll.track, metadata);
            }
        }
        catch (RuntimeException e)
        {
            LOG.debug("Station metadata poll failed for guild {}: {}", guildId, e.toString());
        }
    }

    /** Stops polling for a guild and forgets what was last seen. */
    public void stop(long guildId)
    {
        Poll poll = polls.remove(guildId);
        if (poll != null && poll.future != null)
            poll.future.cancel(false);
    }

    /** The most recent song seen for a guild's stream, or null if none or not polling. */
    public StreamMetadata get(long guildId)
    {
        Poll poll = polls.get(guildId);
        return poll == null ? null : poll.current;
    }

    public void shutdown()
    {
        polls.keySet().forEach(this::stop);
        executor.shutdownNow();
    }
}
