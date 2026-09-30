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

import com.jagrosh.jmusicbot.audio.StreamDiagnostics;
import com.jagrosh.jmusicbot.audio.StreamMetadata;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("StreamDiagnostics Tests")
class StreamDiagnosticsTest
{
    private static final String URI = "http://radio.example/listen/station/radio.mp3";
    private static final long FRAME_NANOS = 20_000_000L;

    private final List<String> infos = new ArrayList<>();
    private final List<String> warns = new ArrayList<>();
    private final long[] now = {1_000_000_000L};
    private final int[] buffered = {2000};
    private AudioTrack track;

    private final StreamDiagnostics.Sink sink = new StreamDiagnostics.Sink()
    {
        @Override
        public void info(String message)
        {
            infos.add(message);
        }

        @Override
        public void warn(String message)
        {
            warns.add(message);
        }
    };

    private final StreamDiagnostics.BufferProbe probe = new StreamDiagnostics.BufferProbe()
    {
        @Override
        public int bufferedMs(AudioTrack t)
        {
            return buffered[0];
        }

        @Override
        public int capacityMs(AudioTrack t)
        {
            return 2000;
        }
    };

    @BeforeEach
    void setUp()
    {
        track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(new AudioTrackInfo("Station", "Radio", Units.DURATION_MS_UNKNOWN, "id", true, URI));
    }

    private StreamDiagnostics diagnostics(boolean verbose)
    {
        return new StreamDiagnostics(7L, verbose, sink, probe, () -> now[0]);
    }

    private void frames(StreamDiagnostics d, int count, boolean provided)
    {
        for (int i = 0; i < count; i++)
        {
            now[0] += FRAME_NANOS;
            d.onFrame(provided, false);
        }
    }

    @Test
    @DisplayName("a gap after the buffer ran down is reported as DRAINED with the lead-up levels")
    void drainedGap()
    {
        StreamDiagnostics d = diagnostics(false);
        d.onTrackStart(track);
        frames(d, 100, true);          // samples at frames 1, 26, 51, 76: all 2000 ms
        buffered[0] = 600;
        frames(d, 25, true);           // sample at frame 101: 600 ms
        buffered[0] = 0;
        frames(d, 30, false);          // 600 ms of silence
        buffered[0] = 40;
        frames(d, 1, true);

        assertEquals(1, warns.size());
        String warn = warns.get(0);
        assertTrue(warn.contains("Stream audio gap in guild 7: 600 ms (30 frames missed)"), warn);
        assertTrue(warn.contains("[2000, 2000, 2000, 2000, 600] ms of 2000 ms"), warn);
        assertTrue(warn.contains("buffer now 40 ms"), warn);
        assertTrue(warn.contains("DRAINED: 600 ms were buffered"), warn);
        assertTrue(warn.contains("about 1200 ms"), warn);
        assertTrue(warn.contains(URI), warn);
    }

    @Test
    @DisplayName("a gap with an already-empty buffer is reported as NO CUSHION")
    void noCushionGap()
    {
        buffered[0] = 20;
        StreamDiagnostics d = diagnostics(false);
        d.onTrackStart(track);
        frames(d, 60, true);
        frames(d, 15, false);          // 300 ms
        frames(d, 1, true);

        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("300 ms (15 frames missed)"), warns.get(0));
        assertTrue(warns.get(0).contains("NO CUSHION"), warns.get(0));
    }

    @Test
    @DisplayName("gaps under 200 ms are not logged individually but count in the session summary")
    void shortGapCountedNotLogged()
    {
        StreamDiagnostics d = diagnostics(false);
        d.onTrackStart(track);
        frames(d, 50, true);
        frames(d, 5, false);           // 100 ms
        frames(d, 50, true);

        assertTrue(warns.isEmpty());

        d.onTrackEnd(track, AudioTrackEndReason.STOPPED);
        assertEquals(1, infos.size());
        String summary = infos.get(0);
        assertTrue(summary.contains("Stream session ended in guild 7 (STOPPED)"), summary);
        assertTrue(summary.contains("100 frames sent, 5 missed"), summary);
        assertTrue(summary.contains("1 gap(s) totalling 100 ms, longest 100 ms"), summary);
    }

    @Test
    @DisplayName("a clean session logs nothing when not verbose")
    void cleanSessionSilent()
    {
        StreamDiagnostics d = diagnostics(false);
        d.onTrackStart(track);
        frames(d, 200, true);
        d.onTrackEnd(track, AudioTrackEndReason.STOPPED);

        assertTrue(infos.isEmpty());
        assertTrue(warns.isEmpty());
    }

    @Test
    @DisplayName("missing frames while paused are not a gap")
    void pausedIsNotAGap()
    {
        StreamDiagnostics d = diagnostics(false);
        d.onTrackStart(track);
        frames(d, 50, true);
        for (int i = 0; i < 100; i++)
        {
            now[0] += FRAME_NANOS;
            d.onFrame(false, true);
        }
        frames(d, 50, true);
        d.onTrackEnd(track, AudioTrackEndReason.STOPPED);

        assertTrue(warns.isEmpty());
        assertTrue(infos.isEmpty(), "no gaps, so no summary either");
    }

    @Test
    @DisplayName("missing frames before the first audio arrives are not a gap")
    void missesBeforeFirstAudioIgnored()
    {
        StreamDiagnostics d = diagnostics(false);
        d.onTrackStart(track);
        frames(d, 60, false);
        frames(d, 10, true);

        assertTrue(warns.isEmpty());
    }

    @Test
    @DisplayName("a gap report says how long ago the station changed song")
    void songChangeInReport()
    {
        StreamDiagnostics d = diagnostics(false);
        d.onTrackStart(track);
        frames(d, 50, true);
        d.onSongChange(new StreamMetadata("Station", "Teardrop", "Massive Attack", null, 3, Instant.now()));
        frames(d, 100, true);          // 2 s later
        buffered[0] = 0;
        frames(d, 20, false);
        frames(d, 1, true);

        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("station reported a new song ('Massive Attack - Teardrop') 2.0s before it"), warns.get(0));
    }

    @Test
    @DisplayName("verbose mode logs connect, first audio, song changes, short gaps and a periodic health line")
    void verboseMode()
    {
        StreamDiagnostics d = diagnostics(true);
        d.onTrackStart(track);
        assertTrue(infos.get(0).contains("Stream connecting in guild 7"), infos.get(0));
        assertTrue(infos.get(0).contains("buffer capacity 2000 ms"), infos.get(0));

        frames(d, 1, true);
        assertTrue(infos.get(1).contains("Stream first audio in guild 7 after 20 ms"), infos.get(1));

        d.onSongChange(new StreamMetadata("Station", "Song", null, null, 0, Instant.now()));
        assertTrue(infos.get(2).contains("Station reports a new song in guild 7: 'Song'"), infos.get(2));

        frames(d, 2, false);           // 40 ms: below the normal threshold, logged in verbose mode
        frames(d, 1, true);
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("40 ms (2 frames missed)"), warns.get(0));

        int before = infos.size();
        frames(d, 1500, true);         // 30 s
        List<String> health = infos.subList(before, infos.size());
        assertEquals(1, health.size());
        assertTrue(health.get(0).contains("Stream health in guild 7 (last 30s)"), health.get(0));
        assertTrue(health.get(0).contains("min 2000 ms, max 2000 ms of 2000 ms"), health.get(0));
        assertTrue(health.get(0).contains("session gaps 1"), health.get(0));

        d.onTrackEnd(track, AudioTrackEndReason.FINISHED);
        assertTrue(infos.get(infos.size() - 1).contains("Stream session ended in guild 7 (FINISHED)"));
    }

    @Test
    @DisplayName("frames with no session, and an end event for another track, are ignored")
    void noSessionIsNoop()
    {
        StreamDiagnostics d = diagnostics(true);
        assertDoesNotThrow(() -> d.onFrame(false, false));
        assertDoesNotThrow(() -> d.onSongChange(new StreamMetadata("S", "T", "A", null, 0, Instant.now())));

        d.onTrackStart(track);
        infos.clear();
        d.onTrackEnd(mock(AudioTrack.class), AudioTrackEndReason.STOPPED);
        assertTrue(infos.isEmpty(), "a different track ending does not close this session");

        frames(d, 1, true);
        assertFalse(infos.isEmpty(), "session is still live");
    }

    @Test
    @DisplayName("an unknown buffer level is reported as such rather than misclassified")
    void unknownBufferLevel()
    {
        buffered[0] = -1;
        StreamDiagnostics d = diagnostics(false);
        d.onTrackStart(track);
        frames(d, 50, true);
        frames(d, 20, false);
        frames(d, 1, true);

        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("[?, ?]"), warns.get(0));
        assertTrue(warns.get(0).contains("Buffer level unavailable"), warns.get(0));
        assertTrue(warns.get(0).contains("buffer now unknown"), warns.get(0));
    }
}
