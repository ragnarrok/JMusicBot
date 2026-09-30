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

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBuffer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.StringJoiner;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/**
 * Records what happens to a live stream on the audio send path and reports every audible gap.
 * <p>
 * A gap is a run of 20 ms send ticks where lavaplayer had no decoded frame to hand over. For
 * each gap the report says how long it lasted and, crucially, how much audio lavaplayer had
 * buffered in the seconds before it. That distinguishes the two ways a live stream stutters:
 * <ul>
 *   <li><b>Drained</b>: the buffer held audio and ran down to zero, so the station stopped
 *       delivering for longer than the cushion.</li>
 *   <li><b>No cushion</b>: the buffer was already empty. A live stream arrives in real time, so
 *       once the initial burst is used up the buffer never refills, and every small pause in the
 *       stream is heard immediately.</li>
 * </ul>
 * Gaps of {@value #MIN_GAP_LOG_MS} ms or more are always logged. Verbose mode additionally logs
 * connects, first audio, station song changes, every gap, and a health line every 30 seconds.
 * <p>
 * {@link #onFrame} runs on the audio send thread every 20 ms, so it does no I/O: report lines
 * are handed to an executor.
 *
 * @author Cohen Singh
 */
public class StreamDiagnostics
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamDiagnostics.class);

    public static final long FRAME_MS = 20;
    /** Gaps at least this long are reported even when verbose mode is off. */
    public static final long MIN_GAP_LOG_MS = 200;
    public static final long SUMMARY_INTERVAL_MS = 30_000;
    /** The buffer level is sampled every this many provided frames (0.5 s). */
    public static final int SAMPLE_EVERY_FRAMES = 25;
    /** How many buffer samples are kept to show the lead-up to a gap (5 s). */
    public static final int HISTORY_SAMPLES = 10;
    /** A buffer at or below this is considered empty when interpreting a gap. */
    static final int EMPTY_BUFFER_MS = 100;

    /** Where report lines go. */
    public interface Sink
    {
        void info(String message);

        void warn(String message);
    }

    /** Reads how much decoded audio lavaplayer currently holds for a track. */
    public interface BufferProbe
    {
        /** Buffered audio in ms, or -1 if it cannot be determined. */
        int bufferedMs(AudioTrack track);

        /** Buffer capacity in ms, or -1 if it cannot be determined. */
        int capacityMs(AudioTrack track);
    }

    /** Probe backed by lavaplayer's frame buffer for the track's active executor. */
    public static final BufferProbe LAVAPLAYER_PROBE = new BufferProbe()
    {
        @Override
        public int bufferedMs(AudioTrack track)
        {
            AudioFrameBuffer buffer = bufferOf(track);
            return buffer == null ? -1 : (int) ((buffer.getFullCapacity() - buffer.getRemainingCapacity()) * FRAME_MS);
        }

        @Override
        public int capacityMs(AudioTrack track)
        {
            AudioFrameBuffer buffer = bufferOf(track);
            return buffer == null ? -1 : (int) (buffer.getFullCapacity() * FRAME_MS);
        }

        private AudioFrameBuffer bufferOf(AudioTrack track)
        {
            try
            {
                if (!(track instanceof InternalAudioTrack internal))
                    return null;
                AudioTrackExecutor executor = internal.getActiveExecutor();
                return executor == null ? null : executor.getAudioBuffer();
            }
            catch (RuntimeException e)
            {
                return null;
            }
        }
    };

    /** Per-connection state. Mutated only on the send thread unless marked volatile. */
    private static final class Session
    {
        final AudioTrack track;
        final String uri;
        final long startNanos;

        boolean firstFrameSeen;
        long gapStartNanos;
        int gapFrames;
        int[] historyAtGapStart;

        final int[] history = new int[HISTORY_SAMPLES];
        int historyCount;
        int historyNext;
        int sampleCountdown;

        long windowProvided;
        long windowMissed;
        int windowMinMs = Integer.MAX_VALUE;
        int windowMaxMs = -1;
        long lastSummaryNanos;

        long totalProvided;
        long totalMissed;
        int gaps;
        long gapMsTotal;
        long longestGapMs;

        volatile long lastSongChangeNanos;
        volatile String lastSong;

        Session(AudioTrack track, long now)
        {
            this.track = track;
            this.uri = track.getInfo() == null ? "unknown" : track.getInfo().uri;
            this.startNanos = now;
            this.lastSummaryNanos = now;
        }
    }

    private final long guildId;
    private final boolean verbose;
    private final Sink sink;
    private final BufferProbe probe;
    private final LongSupplier nanoClock;
    private volatile Session session;

    /**
     * Creates diagnostics that log through SLF4J, handing each line to {@code logExecutor} so
     * the send thread never blocks on log I/O. A null executor logs inline.
     */
    public StreamDiagnostics(long guildId, boolean verbose, Executor logExecutor)
    {
        this(guildId, verbose, executorSink(logExecutor), LAVAPLAYER_PROBE, System::nanoTime);
    }

    public StreamDiagnostics(long guildId, boolean verbose, Sink sink, BufferProbe probe, LongSupplier nanoClock)
    {
        this.guildId = guildId;
        this.verbose = verbose;
        this.sink = sink;
        this.probe = probe;
        this.nanoClock = nanoClock;
    }

    private static Sink executorSink(Executor executor)
    {
        return new Sink()
        {
            @Override
            public void info(String message)
            {
                submit(() -> LOG.info(message));
            }

            @Override
            public void warn(String message)
            {
                submit(() -> LOG.warn(message));
            }

            private void submit(Runnable task)
            {
                if (executor == null)
                {
                    task.run();
                    return;
                }
                try
                {
                    executor.execute(task);
                }
                catch (RejectedExecutionException e)
                {
                    task.run(); // shutting down: log inline rather than lose the line
                }
            }
        };
    }

    public boolean isVerbose()
    {
        return verbose;
    }

    /** A live stream started (or was reconnected). Begins a fresh session. */
    public void onTrackStart(AudioTrack track)
    {
        if (track == null)
            return;
        Session s = new Session(track, nanoClock.getAsLong());
        session = s;
        if (verbose)
            sink.info(String.format("Stream connecting in guild %d: %s (buffer capacity %s)",
                    guildId, s.uri, ms(probe.capacityMs(track))));
    }

    /** The stream's track ended. Closes the session and reports its totals. */
    public void onTrackEnd(AudioTrack track, AudioTrackEndReason reason)
    {
        Session s = session;
        if (s == null || (track != null && s.track != track))
            return;
        session = null;
        if (s.gaps == 0 && !verbose)
            return;
        long now = nanoClock.getAsLong();
        long total = s.totalProvided + s.totalMissed;
        double missPercent = total == 0 ? 0 : 100.0 * s.totalMissed / total;
        sink.info(String.format(
                "Stream session ended in guild %d (%s) after %s: %d frames sent, %d missed (%.2f%%), %d gap(s) totalling %d ms, longest %d ms. Stream: %s",
                guildId, reason, duration(now - s.startNanos), s.totalProvided, s.totalMissed, missPercent,
                s.gaps, s.gapMsTotal, s.longestGapMs, s.uri));
    }

    /** The station reported a new song. Recorded so gaps can be related to track changes. */
    public void onSongChange(StreamMetadata metadata)
    {
        Session s = session;
        if (s == null || metadata == null)
            return;
        s.lastSongChangeNanos = nanoClock.getAsLong();
        s.lastSong = metadata.songLine();
        if (verbose)
            sink.info(String.format("Station reports a new song in guild %d: '%s' (buffer now %s)",
                    guildId, s.lastSong, ms(probe.bufferedMs(s.track))));
    }

    /**
     * Called once per 20 ms send tick.
     *
     * @param provided whether lavaplayer had a frame for this tick
     * @param paused   whether the player is paused (missing frames are then expected)
     */
    public void onFrame(boolean provided, boolean paused)
    {
        Session s = session;
        if (s == null)
            return;
        long now = nanoClock.getAsLong();

        if (provided)
        {
            s.totalProvided++;
            s.windowProvided++;
            if (!s.firstFrameSeen)
            {
                s.firstFrameSeen = true;
                if (verbose)
                    sink.info(String.format("Stream first audio in guild %d after %d ms (buffer %s)",
                            guildId, (now - s.startNanos) / 1_000_000L, ms(probe.bufferedMs(s.track))));
            }
            if (s.gapStartNanos != 0)
                endGap(s, now);
            if (--s.sampleCountdown <= 0)
            {
                s.sampleCountdown = SAMPLE_EVERY_FRAMES;
                sample(s);
            }
        }
        else if (paused)
        {
            s.gapStartNanos = 0; // silence while paused is not a gap
        }
        else if (s.firstFrameSeen)
        {
            if (s.gapStartNanos == 0)
            {
                s.gapStartNanos = now;
                s.gapFrames = 0;
                s.historyAtGapStart = historySnapshot(s);
            }
            s.gapFrames++;
            s.totalMissed++;
            s.windowMissed++;
        }

        if (verbose && now - s.lastSummaryNanos >= SUMMARY_INTERVAL_MS * 1_000_000L)
            summary(s, now);
    }

    private void sample(Session s)
    {
        int buffered = probe.bufferedMs(s.track);
        s.history[s.historyNext] = buffered;
        s.historyNext = (s.historyNext + 1) % HISTORY_SAMPLES;
        if (s.historyCount < HISTORY_SAMPLES)
            s.historyCount++;
        if (buffered >= 0)
        {
            s.windowMinMs = Math.min(s.windowMinMs, buffered);
            s.windowMaxMs = Math.max(s.windowMaxMs, buffered);
        }
    }

    /** Buffer samples oldest to newest. */
    private static int[] historySnapshot(Session s)
    {
        int[] out = new int[s.historyCount];
        int start = (s.historyNext - s.historyCount + HISTORY_SAMPLES) % HISTORY_SAMPLES;
        for (int i = 0; i < s.historyCount; i++)
            out[i] = s.history[(start + i) % HISTORY_SAMPLES];
        return out;
    }

    private void endGap(Session s, long now)
    {
        long gapMs = Math.max(s.gapFrames * FRAME_MS, (now - s.gapStartNanos) / 1_000_000L);
        long gapStart = s.gapStartNanos;
        int[] before = s.historyAtGapStart;
        s.gapStartNanos = 0;
        s.historyAtGapStart = null;
        s.gaps++;
        s.gapMsTotal += gapMs;
        s.longestGapMs = Math.max(s.longestGapMs, gapMs);

        if (gapMs < MIN_GAP_LOG_MS && !verbose)
            return;

        StringBuilder message = new StringBuilder();
        message.append(String.format("Stream audio gap in guild %d: %d ms (%d frames missed). ", guildId, gapMs, s.gapFrames));
        message.append("Buffer before the gap (oldest to newest, 0.5s apart): ").append(join(before))
                .append(" of ").append(ms(probe.capacityMs(s.track)))
                .append("; buffer now ").append(ms(probe.bufferedMs(s.track))).append(". ");
        message.append(interpret(before, gapMs)).append(' ');
        message.append("Connected ").append(duration(gapStart - s.startNanos)).append(" before the gap");
        long songChange = s.lastSongChangeNanos;
        if (songChange != 0)
            message.append(String.format("; station reported a new song ('%s') %s before it",
                    s.lastSong, duration(gapStart - songChange)));
        message.append(". Stream: ").append(s.uri);
        sink.warn(message.toString());
    }

    /** One-sentence reading of the buffer history for the log line. */
    static String interpret(int[] before, long gapMs)
    {
        if (before == null || before.length == 0)
            return "No buffer samples yet, so the cause cannot be classified.";
        int last = before[before.length - 1];
        if (last < 0)
            return "Buffer level unavailable, so the cause cannot be classified.";
        if (last <= EMPTY_BUFFER_MS)
            return "NO CUSHION: the buffer was already empty before the gap, so any pause in the stream is heard at once. "
                    + "A live stream arrives in real time and never refills the buffer on its own.";
        return String.format("DRAINED: %d ms were buffered and ran out, so the station delivered no audio for about %d ms.",
                last, last + gapMs);
    }

    private void summary(Session s, long now)
    {
        s.lastSummaryNanos = now;
        String min = s.windowMinMs == Integer.MAX_VALUE ? "n/a" : s.windowMinMs + " ms";
        String max = s.windowMaxMs < 0 ? "n/a" : s.windowMaxMs + " ms";
        sink.info(String.format(
                "Stream health in guild %d (last %ds): buffer now %s, min %s, max %s of %s; frames sent %d, missed %d; session gaps %d",
                guildId, SUMMARY_INTERVAL_MS / 1000, ms(probe.bufferedMs(s.track)), min, max, ms(probe.capacityMs(s.track)),
                s.windowProvided, s.windowMissed, s.gaps));
        s.windowProvided = 0;
        s.windowMissed = 0;
        s.windowMinMs = Integer.MAX_VALUE;
        s.windowMaxMs = -1;
    }

    private static String join(int[] samples)
    {
        if (samples == null || samples.length == 0)
            return "[none]";
        StringJoiner joiner = new StringJoiner(", ", "[", "] ms");
        for (int sample : samples)
            joiner.add(sample < 0 ? "?" : Integer.toString(sample));
        return joiner.toString();
    }

    private static String ms(int value)
    {
        return value < 0 ? "unknown" : value + " ms";
    }

    private static String duration(long nanos)
    {
        long totalSeconds = Math.max(0, nanos / 1_000_000_000L);
        if (totalSeconds < 60)
            return String.format("%d.%ds", totalSeconds, Math.max(0, nanos / 100_000_000L) % 10);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0 ? String.format("%dh%02dm%02ds", hours, minutes, seconds) : String.format("%dm%02ds", minutes, seconds);
    }
}
