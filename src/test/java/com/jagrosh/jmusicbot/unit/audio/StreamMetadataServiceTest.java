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
import com.jagrosh.jmusicbot.audio.StreamMetadataService;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StreamMetadataService Tests")
class StreamMetadataServiceTest
{
    private static final long GUILD = 1L;
    private static final String URI = "https://radio.example.com/listen/station/radio.mp3";

    private StreamMetadataFetcher fetcher;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private StreamMetadataService.Listener listener;
    private StreamMetadataService service;

    @BeforeEach
    void setUp()
    {
        fetcher = mock(StreamMetadataFetcher.class);
        executor = mock(ScheduledExecutorService.class);
        future = mock(ScheduledFuture.class);
        listener = mock(StreamMetadataService.Listener.class);
        doReturn(future).when(executor).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        service = new StreamMetadataService(true, 15, fetcher, executor);
    }

    private static AudioTrack track(String uri, boolean stream)
    {
        AudioTrack track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(new AudioTrackInfo("t", "a", stream ? Units.DURATION_MS_UNKNOWN : 1000, "id", stream, uri));
        return track;
    }

    private static StreamMetadata song(String title)
    {
        return new StreamMetadata("Station", title, "Artist", null, 5, Instant.now());
    }

    private Runnable capturePoll()
    {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(captor.capture(), eq(0L), eq(15L), eq(TimeUnit.SECONDS));
        return captor.getValue();
    }

    @Test
    @DisplayName("start() schedules a poll and reports the first song, then only changes")
    void reportsChangesOnly()
    {
        AudioTrack track = track(URI, true);
        service.start(GUILD, track, listener);
        Runnable poll = capturePoll();

        when(fetcher.fetch(URI)).thenReturn(Optional.of(song("One")));
        poll.run();
        verify(listener).onMetadataChanged(eq(GUILD), eq(track), argThat(md -> "One".equals(md.title())));
        assertEquals("One", service.get(GUILD).title());

        // Same song again (different listener count) is not reported.
        when(fetcher.fetch(URI)).thenReturn(Optional.of(new StreamMetadata("Station", "One", "Artist", null, 9, Instant.now())));
        poll.run();
        verify(listener, times(1)).onMetadataChanged(anyLong(), any(), any());
        assertEquals(9, service.get(GUILD).listeners(), "but the latest snapshot is kept");

        when(fetcher.fetch(URI)).thenReturn(Optional.of(song("Two")));
        poll.run();
        verify(listener).onMetadataChanged(eq(GUILD), eq(track), argThat(md -> "Two".equals(md.title())));
    }

    @Test
    @DisplayName("a failed fetch keeps the last known song")
    void failedFetchKeepsLast()
    {
        service.start(GUILD, track(URI, true), listener);
        Runnable poll = capturePoll();
        when(fetcher.fetch(URI)).thenReturn(Optional.of(song("One")));
        poll.run();

        when(fetcher.fetch(URI)).thenReturn(Optional.empty());
        poll.run();

        assertEquals("One", service.get(GUILD).title());
        verify(listener, times(1)).onMetadataChanged(anyLong(), any(), any());
    }

    @Test
    @DisplayName("stop() cancels the poll and forgets the song")
    void stopCancels()
    {
        service.start(GUILD, track(URI, true), listener);
        Runnable poll = capturePoll();
        when(fetcher.fetch(URI)).thenReturn(Optional.of(song("One")));
        poll.run();

        service.stop(GUILD);

        verify(future).cancel(false);
        assertNull(service.get(GUILD));

        // A late run of the old task must not resurrect anything.
        poll.run();
        assertNull(service.get(GUILD));
        verify(listener, times(1)).onMetadataChanged(anyLong(), any(), any());
    }

    @Test
    @DisplayName("start() again replaces the previous poll")
    void restartReplaces()
    {
        service.start(GUILD, track(URI, true), listener);
        service.start(GUILD, track(URI, true), listener);

        verify(future).cancel(false);
        verify(executor, times(2)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("non-stream tracks, unsupported URLs and a disabled service never poll")
    void noPollCases()
    {
        service.start(GUILD, track("https://example.com/song.mp3", false), listener);
        service.start(GUILD, track("file:///radio.mp3", true), listener);
        new StreamMetadataService(false, 15, fetcher, executor).start(GUILD, track(URI, true), listener);

        verify(executor, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        assertNull(service.get(GUILD));
    }

    @Test
    @DisplayName("a fetcher exception does not kill the poll")
    void fetcherExceptionSwallowed()
    {
        service.start(GUILD, track(URI, true), listener);
        Runnable poll = capturePoll();
        when(fetcher.fetch(URI)).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(poll::run);
        verify(listener, never()).onMetadataChanged(anyLong(), any(), any());
    }

    @Test
    @DisplayName("shutdown() stops polls and the executor")
    void shutdown()
    {
        service.start(GUILD, track(URI, true), listener);

        service.shutdown();

        verify(future).cancel(false);
        verify(executor).shutdownNow();
        assertNull(service.get(GUILD));
    }
}
