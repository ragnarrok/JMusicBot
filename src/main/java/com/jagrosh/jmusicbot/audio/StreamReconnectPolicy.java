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
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

/**
 * Decides whether, and how soon, a live stream that ended unexpectedly should be reconnected.
 * <p>
 * The policy tracks consecutive failed attempts and applies exponential backoff between them:
 * attempt N waits {@code min(baseDelay * 2^(N-1), maxDelay)} seconds. A successful stretch of
 * playback should call {@link #reset()} so the next drop starts again from the base delay.
 * <p>
 * Only track end reasons that indicate a drop are considered unexpected: {@code FINISHED}
 * (a live stream never finishes on its own), {@code LOAD_FAILED}, and {@code STOPPED} when the
 * handler itself requested a restart because the stream stalled. User-initiated stops, skips,
 * and replacements never trigger a reconnect.
 *
 * @author Cohen Singh
 */
public final class StreamReconnectPolicy
{
    /** Guards against overflow when computing the exponential backoff. */
    private static final int MAX_BACKOFF_EXPONENT = 20;

    private final boolean enabled;
    private final long baseDelaySeconds;
    private final long maxDelaySeconds;
    private final int maxAttempts;
    private volatile int attempts;

    public StreamReconnectPolicy(boolean enabled, long baseDelaySeconds, long maxDelaySeconds, int maxAttempts)
    {
        this.enabled = enabled;
        this.baseDelaySeconds = Math.max(0L, baseDelaySeconds);
        this.maxDelaySeconds = Math.max(this.baseDelaySeconds, maxDelaySeconds);
        this.maxAttempts = Math.max(0, maxAttempts);
    }

    public static StreamReconnectPolicy fromConfig(BotConfig config)
    {
        return new StreamReconnectPolicy(
                config.persistStreams(),
                config.getStreamReconnectDelaySeconds(),
                config.getStreamReconnectMaxDelaySeconds(),
                config.getStreamReconnectMaxAttempts());
    }

    public static StreamReconnectPolicy disabled()
    {
        return new StreamReconnectPolicy(false, 0, 0, 0);
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    /**
     * Returns true if the track is a live stream (no known duration), such as internet radio.
     */
    public static boolean isLiveStream(AudioTrack track)
    {
        if (track == null)
            return false;
        AudioTrackInfo info = track.getInfo();
        if (info == null)
            return false;
        return info.isStream || info.length == Units.DURATION_MS_UNKNOWN;
    }

    /**
     * Returns true if the given end reason means the stream dropped rather than being ended on purpose.
     *
     * @param reason           why the track ended
     * @param restartRequested true if the handler stopped the track itself to restart a stalled stream
     */
    public boolean isUnexpectedEnd(AudioTrackEndReason reason, boolean restartRequested)
    {
        if (reason == null)
            return false;
        return switch (reason)
        {
            case FINISHED, LOAD_FAILED -> true;
            case STOPPED -> restartRequested;
            case REPLACED, CLEANUP -> false;
        };
    }

    /**
     * Registers a reconnect attempt and returns how many seconds to wait before it.
     *
     * @return the delay in seconds, or {@code -1} if the attempt limit has been reached
     */
    public synchronized long nextDelaySeconds()
    {
        if (maxAttempts > 0 && attempts >= maxAttempts)
            return -1;
        attempts++;
        int exponent = Math.min(attempts - 1, MAX_BACKOFF_EXPONENT);
        long delay = baseDelaySeconds * (1L << exponent);
        return Math.min(delay, maxDelaySeconds);
    }

    /** Number of consecutive reconnect attempts made since the last {@link #reset()}. */
    public int getAttempts()
    {
        return attempts;
    }

    public int getMaxAttempts()
    {
        return maxAttempts;
    }

    /** Clears the consecutive attempt counter, e.g. after the stream has played stably for a while. */
    public synchronized void reset()
    {
        attempts = 0;
    }
}
