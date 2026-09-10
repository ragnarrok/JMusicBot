/*
 * Copyright 2026 Arif Banai (arif-banai)
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

import com.jagrosh.jmusicbot.TestBase;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.NowPlayingHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.StreamMetadata;
import com.jagrosh.jmusicbot.audio.StreamMetadataService;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AudioHandler Tests")
public class AudioHandlerTest extends TestBase {

    @Mock
    private SelfMember selfMember;
    @Mock
    private GuildVoiceState voiceState;

    private AudioHandler audioHandler;

    @BeforeEach
    @Override
    public void setUp() {
        super.setUp();
        when(settings.getQueueType()).thenReturn(QueueType.FAIR);
        when(settings.getRepeatMode()).thenReturn(RepeatMode.OFF);

        audioHandler = newHandler();
    }

    /**
     * AudioHandler's constructor is not visible, so use reflection to instantiate it for testing.
     * Config-dependent state (e.g. stream persistence) is read in the constructor, so tests that
     * stub config must call this again afterwards.
     */
    private AudioHandler newHandler() {
        try {
            var constructor = AudioHandler.class.getDeclaredConstructor(
                    playerManager.getClass().getInterfaces().length > 0 ? playerManager.getClass().getInterfaces()[0] : playerManager.getClass(),
                    guild.getClass().getInterfaces().length > 0 ? guild.getClass().getInterfaces()[0] : guild.getClass(),
                    audioPlayer.getClass().getInterfaces().length > 0 ? audioPlayer.getClass().getInterfaces()[0] : audioPlayer.getClass()
            );
            constructor.setAccessible(true);
            return (AudioHandler) constructor.newInstance(playerManager, guild, audioPlayer);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate AudioHandler via reflection", e);
        }
    }

    // ==================== Add Track Tests ====================

    @Nested
    @DisplayName("Add Track Operations")
    class AddTrackTests
    {
        @Test
        @DisplayName("addTrack() plays immediately when nothing is playing")
        public void testAddTrackWhenNothingPlaying() {
            QueuedTrack qtrack = mock(QueuedTrack.class);
            AudioTrack track = mock(AudioTrack.class);
            when(qtrack.getTrack()).thenReturn(track);
            when(audioPlayer.getPlayingTrack()).thenReturn(null);

            int result = audioHandler.addTrack(qtrack);

            assertEquals(-1, result);
            verify(audioPlayer).playTrack(track);
        }

        @Test
        @DisplayName("addTrack() queues track when something is playing")
        public void testAddTrackWhenSomethingPlaying() {
            QueuedTrack qtrack = mock(QueuedTrack.class);
            AudioTrack track = mock(AudioTrack.class);
            AudioTrackInfo info = new AudioTrackInfo("Title", "Author", 1000, "identifier", true, "uri");
            when(track.getInfo()).thenReturn(info);
            when(qtrack.getTrack()).thenReturn(track);
            when(audioPlayer.getPlayingTrack()).thenReturn(mock(AudioTrack.class));

            int result = audioHandler.addTrack(qtrack);

            assertTrue(result >= 0);
            assertEquals(1, audioHandler.getQueue().size());
        }

        @Test
        @DisplayName("addTrackToFront() plays immediately when nothing is playing")
        public void testAddTrackToFrontWhenNothingPlaying() {
            QueuedTrack qtrack = mock(QueuedTrack.class);
            AudioTrack track = mock(AudioTrack.class);
            when(qtrack.getTrack()).thenReturn(track);
            when(audioPlayer.getPlayingTrack()).thenReturn(null);

            int result = audioHandler.addTrackToFront(qtrack);

            assertEquals(-1, result);
            verify(audioPlayer).playTrack(track);
        }

        @Test
        @DisplayName("addTrackToFront() adds to position 0 when something is playing")
        public void testAddTrackToFrontWhenSomethingPlaying() {
            // First add a track to the queue
            QueuedTrack qtrack1 = mock(QueuedTrack.class);
            AudioTrack track1 = mock(AudioTrack.class);
            AudioTrackInfo info1 = new AudioTrackInfo("Track 1", "Author", 1000, "id1", true, "uri1");
            when(track1.getInfo()).thenReturn(info1);
            when(qtrack1.getTrack()).thenReturn(track1);
            when(audioPlayer.getPlayingTrack()).thenReturn(mock(AudioTrack.class));
            audioHandler.addTrack(qtrack1);

            // Now add to front
            QueuedTrack qtrack2 = mock(QueuedTrack.class);
            AudioTrack track2 = mock(AudioTrack.class);
            AudioTrackInfo info2 = new AudioTrackInfo("Track 2", "Author", 1000, "id2", true, "uri2");
            when(track2.getInfo()).thenReturn(info2);
            when(qtrack2.getTrack()).thenReturn(track2);

            int result = audioHandler.addTrackToFront(qtrack2);

            assertEquals(0, result);
            assertEquals(2, audioHandler.getQueue().size());
        }
    }

    // ==================== Stop and Clear Tests ====================

    @Nested
    @DisplayName("Stop and Clear Operations")
    class StopAndClearTests
    {
        @Test
        @DisplayName("stopAndClear() stops playback and clears queue")
        public void testStopAndClear() {
            audioHandler.stopAndClear();

            verify(audioPlayer).stopTrack();
            assertTrue(audioHandler.getQueue().isEmpty());
        }

        @Test
        @DisplayName("stopAndClear() can be called multiple times safely")
        public void testStopAndClearMultipleTimes() {
            audioHandler.stopAndClear();
            audioHandler.stopAndClear();

            verify(audioPlayer, times(2)).stopTrack();
        }

        @Test
        @DisplayName("stopAndClearQueuePreserveHistory() does not add current track and clears queue only")
        public void testStopAndClearQueuePreserveHistoryDoesNotAddCurrentTrack() {
            AudioTrack playingTrack = mock(AudioTrack.class);
            AudioTrackInfo playingInfo = new AudioTrackInfo("Playing", "Author", 1000, "playing-id", false, "uri");
            when(playingTrack.getInfo()).thenReturn(playingInfo);
            when(audioPlayer.getPlayingTrack()).thenReturn(playingTrack);

            // Put one item in the queue to verify it gets cleared.
            QueuedTrack queued = mock(QueuedTrack.class);
            AudioTrack queuedTrack = mock(AudioTrack.class);
            AudioTrackInfo queuedInfo = new AudioTrackInfo("Queued", "Author", 1000, "queued-id", false, "uri2");
            when(queuedTrack.getInfo()).thenReturn(queuedInfo);
            when(queued.getTrack()).thenReturn(queuedTrack);
            audioHandler.addTrack(queued);

            audioHandler.stopAndClearQueuePreserveHistory();

            verify(audioPlayer).stopTrack();
            assertTrue(audioHandler.getQueue().isEmpty());
            assertTrue(audioHandler.getPreviousTracks().isEmpty());
        }

        @Test
        @DisplayName("stopAndClearQueuePreserveHistory() leaves history unchanged when nothing is playing")
        public void testStopAndClearQueuePreserveHistoryNoCurrentTrack() {
            when(audioPlayer.getPlayingTrack()).thenReturn(null);

            audioHandler.stopAndClearQueuePreserveHistory();

            verify(audioPlayer).stopTrack();
            assertTrue(audioHandler.getQueue().isEmpty());
            assertTrue(audioHandler.getPreviousTracks().isEmpty());
        }
    }

    // ==================== isMusicPlaying Tests ====================

    @Nested
    @DisplayName("isMusicPlaying")
    class IsMusicPlayingTests
    {
        @Test
        @DisplayName("isMusicPlaying() returns true when connected and playing")
        public void testIsMusicPlayingTrue() {
            when(jda.getGuildById(anyLong())).thenReturn(guild);
            when(guild.getSelfMember()).thenReturn(selfMember);
            when(selfMember.getVoiceState()).thenReturn(voiceState);
            when(voiceState.getChannel()).thenReturn(audioChannel);
            when(audioPlayer.getPlayingTrack()).thenReturn(audioTrack);

            assertTrue(audioHandler.isMusicPlaying(jda));
        }

        @Test
        @DisplayName("isMusicPlaying() returns false when not in voice channel")
        public void testIsMusicPlayingFalseNotInVoice() {
            when(jda.getGuildById(anyLong())).thenReturn(guild);
            when(guild.getSelfMember()).thenReturn(selfMember);
            when(selfMember.getVoiceState()).thenReturn(voiceState);
            when(voiceState.getChannel()).thenReturn(null);
            when(audioPlayer.getPlayingTrack()).thenReturn(audioTrack);

            assertFalse(audioHandler.isMusicPlaying(jda));
        }

        @Test
        @DisplayName("isMusicPlaying() returns false when nothing is playing")
        public void testIsMusicPlayingFalseNoTrack() {
            when(jda.getGuildById(anyLong())).thenReturn(guild);
            when(guild.getSelfMember()).thenReturn(selfMember);
            when(selfMember.getVoiceState()).thenReturn(voiceState);
            when(voiceState.getChannel()).thenReturn(audioChannel);
            when(audioPlayer.getPlayingTrack()).thenReturn(null);

            assertFalse(audioHandler.isMusicPlaying(jda));
        }
    }

    // ==================== Vote Tests ====================

    @Nested
    @DisplayName("Vote Tracking")
    class VoteTests
    {
        @Test
        @DisplayName("getVotes() returns empty set initially")
        public void testGetVotesInitiallyEmpty() {
            assertTrue(audioHandler.getVotes().isEmpty());
        }

        @Test
        @DisplayName("votes can be added and retrieved")
        public void testAddVote() {
            audioHandler.getVotes().add("user123");

            assertEquals(1, audioHandler.getVotes().size());
            assertTrue(audioHandler.getVotes().contains("user123"));
        }

        @Test
        @DisplayName("duplicate votes are not added")
        public void testDuplicateVotes() {
            audioHandler.getVotes().add("user123");
            audioHandler.getVotes().add("user123");

            assertEquals(1, audioHandler.getVotes().size());
        }
    }

    // ==================== Queue Operations Tests ====================

    @Nested
    @DisplayName("Queue Operations")
    class QueueOperationsTests
    {
        @Test
        @DisplayName("getQueue() returns non-null queue")
        public void testGetQueueNotNull() {
            assertNotNull(audioHandler.getQueue());
        }

        @Test
        @DisplayName("queue starts empty")
        public void testQueueStartsEmpty() {
            assertTrue(audioHandler.getQueue().isEmpty());
            assertEquals(0, audioHandler.getQueue().size());
        }

        @Test
        @DisplayName("setQueueType() changes queue type")
        public void testSetQueueType() {
            // Add a track first
            QueuedTrack qtrack = mock(QueuedTrack.class);
            AudioTrack track = mock(AudioTrack.class);
            AudioTrackInfo info = new AudioTrackInfo("Title", "Author", 1000, "identifier", true, "uri");
            when(track.getInfo()).thenReturn(info);
            when(qtrack.getTrack()).thenReturn(track);
            when(audioPlayer.getPlayingTrack()).thenReturn(mock(AudioTrack.class));
            audioHandler.addTrack(qtrack);

            // Change queue type
            audioHandler.setQueueType(QueueType.LINEAR);

            // Queue should still exist
            assertNotNull(audioHandler.getQueue());
        }
    }

    // ==================== Player Access Tests ====================

    @Nested
    @DisplayName("Player Access")
    class PlayerAccessTests
    {
        @Test
        @DisplayName("getPlayer() returns the audio player")
        public void testGetPlayer() {
            assertEquals(audioPlayer, audioHandler.getPlayer());
        }
    }

    // ==================== Last Reason Tests ====================

    @Nested
    @DisplayName("Last Reason")
    class LastReasonTests
    {
        @Test
        @DisplayName("setLastReason() stores reason")
        public void testSetLastReason() {
            // Just verify it doesn't throw
            assertDoesNotThrow(() -> audioHandler.setLastReason("Test reason"));
        }
    }

    // ==================== Previous Tracks Tests ====================

    @Nested
    @DisplayName("Previous Tracks (History)")
    class PreviousTracksTests
    {
        @Test
        @DisplayName("getPreviousTracks() returns list")
        public void testGetPreviousTracks() {
            assertNotNull(audioHandler.getPreviousTracks());
        }

        @Test
        @DisplayName("getPreviousTracks() starts empty")
        public void testGetPreviousTracksStartsEmpty() {
            assertTrue(audioHandler.getPreviousTracks().isEmpty());
        }
    }

    @Nested
    @DisplayName("Track Start History")
    class TrackStartHistoryTests
    {
        @Test
        @DisplayName("onTrackStart() adds started track to history")
        void onTrackStart_addsToHistory()
        {
            AudioTrack startedTrack = createTrack("started-track", "Started Track", "https://example.com/started-track");
            when(bot.getNowplayingHandler()).thenReturn(mock(com.jagrosh.jmusicbot.audio.NowPlayingHandler.class));

            audioHandler.onTrackStart(audioPlayer, startedTrack);

            assertEquals(1, audioHandler.getPreviousTracks().size());
        }

        @Test
        @DisplayName("onTrackEnd(FINISHED) does not add track to history")
        void onTrackEnd_finished_doesNotAddToHistory()
        {
            AudioTrack playedTrack = createTrack("played-finished", "Played Finished", "https://example.com/played-finished");
            enqueueNextTrack("queued-finished", "Queued Finished", "https://example.com/queued-finished");

            audioHandler.onTrackEnd(audioPlayer, playedTrack, AudioTrackEndReason.FINISHED);

            assertTrue(audioHandler.getPreviousTracks().isEmpty());
        }

        @Test
        @DisplayName("onTrackEnd(STOPPED) does not add track to history")
        void onTrackEnd_stopped_doesNotAddToHistory()
        {
            AudioTrack playedTrack = createTrack("played-stopped", "Played Stopped", "https://example.com/played-stopped");
            enqueueNextTrack("queued-stopped", "Queued Stopped", "https://example.com/queued-stopped");

            audioHandler.onTrackEnd(audioPlayer, playedTrack, AudioTrackEndReason.STOPPED);

            assertTrue(audioHandler.getPreviousTracks().isEmpty());
        }

        @Test
        @DisplayName("onTrackEnd(STOPPED) does not add history across repeated calls")
        void onTrackEnd_stopped_repeatedCalls_doNotAddHistory()
        {
            AudioTrack firstStoppedTrack = createTrack("played-guard-1", "Played Guard 1", "https://example.com/played-guard-1");
            enqueueNextTrack("queued-guard-1", "Queued Guard 1", "https://example.com/queued-guard-1");
            audioHandler.onTrackEnd(audioPlayer, firstStoppedTrack, AudioTrackEndReason.STOPPED);
            assertTrue(audioHandler.getPreviousTracks().isEmpty());

            AudioTrack secondStoppedTrack = createTrack("played-guard-2", "Played Guard 2", "https://example.com/played-guard-2");
            enqueueNextTrack("queued-guard-2", "Queued Guard 2", "https://example.com/queued-guard-2");
            audioHandler.onTrackEnd(audioPlayer, secondStoppedTrack, AudioTrackEndReason.STOPPED);
            assertTrue(audioHandler.getPreviousTracks().isEmpty());
        }

        @Test
        @DisplayName("onTrackEnd(LOAD_FAILED) does not add track to history")
        void onTrackEnd_loadFailed_doesNotAddToHistory()
        {
            AudioTrack playedTrack = createTrack("played-load-failed", "Played Load Failed", "https://example.com/played-load-failed");
            enqueueNextTrack("queued-load-failed", "Queued Load Failed", "https://example.com/queued-load-failed");

            audioHandler.onTrackEnd(audioPlayer, playedTrack, AudioTrackEndReason.LOAD_FAILED);

            assertTrue(audioHandler.getPreviousTracks().isEmpty());
        }

        @Test
        @DisplayName("stopAndClearQueuePreserveHistory() does not duplicate a track already added on start")
        void stopAndClearQueuePreserveHistory_doesNotDuplicateTrackAlreadyInHistory()
        {
            AudioTrack startedTrack = createTrack("started-dedup", "Started Dedup", "https://example.com/started-dedup");
            when(audioPlayer.getPlayingTrack()).thenReturn(startedTrack);
            when(bot.getNowplayingHandler()).thenReturn(mock(com.jagrosh.jmusicbot.audio.NowPlayingHandler.class));

            audioHandler.onTrackStart(audioPlayer, startedTrack);
            audioHandler.stopAndClearQueuePreserveHistory();

            assertEquals(1, audioHandler.getPreviousTracks().size());
        }

        private AudioTrack createTrack(String identifier, String title, String uri)
        {
            AudioTrack track = mock(AudioTrack.class);
            AudioTrack clone = mock(AudioTrack.class);
            AudioTrackInfo info = new AudioTrackInfo(title, "Author", 1000, identifier, false, uri);
            when(track.getInfo()).thenReturn(info);
            when(track.getIdentifier()).thenReturn(identifier);
            when(track.makeClone()).thenReturn(clone);
            return track;
        }

        private void enqueueNextTrack(String identifier, String title, String uri)
        {
            AudioTrack track = createTrack(identifier, title, uri);
            QueuedTrack queuedTrack = mock(QueuedTrack.class);
            when(queuedTrack.getTrack()).thenReturn(track);
            when(queuedTrack.getIdentifier()).thenReturn(0L);
            audioHandler.getQueue().add(queuedTrack);
        }
    }

    // ==================== Stream Persistence Tests ====================

    @Nested
    @DisplayName("Stream Persistence")
    class StreamPersistenceTests
    {
        private static final String STREAM_URI = "https://radio.example.com/listen/station/radio.mp3";

        // Created manually: TestBase only opens @Mock fields on the outer test instance.
        private final ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);

        private void enablePersistence(int maxAttempts)
        {
            when(config.persistStreams()).thenReturn(true);
            when(config.getStreamReconnectDelaySeconds()).thenReturn(5L);
            when(config.getStreamReconnectMaxDelaySeconds()).thenReturn(60L);
            when(config.getStreamReconnectMaxAttempts()).thenReturn(maxAttempts);
            when(bot.getNowplayingHandler()).thenReturn(mock(NowPlayingHandler.class));
            doReturn(scheduledFuture).when(threadpool).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
            audioHandler = newHandler();
        }

        private AudioTrack createStreamTrack()
        {
            AudioTrack track = mock(AudioTrack.class);
            AudioTrack clone = mock(AudioTrack.class);
            AudioTrackInfo info = new AudioTrackInfo("Station", "Azuracast", Units.DURATION_MS_UNKNOWN, "stream-id", true, STREAM_URI);
            when(track.getInfo()).thenReturn(info);
            when(track.getIdentifier()).thenReturn("stream-id");
            when(track.makeClone()).thenReturn(clone);
            return track;
        }

        private AudioTrack createRegularTrack()
        {
            AudioTrack track = mock(AudioTrack.class);
            AudioTrack clone = mock(AudioTrack.class);
            AudioTrackInfo info = new AudioTrackInfo("Song", "Artist", 180_000, "song-id", false, "https://example.com/song");
            when(track.getInfo()).thenReturn(info);
            when(track.getIdentifier()).thenReturn("song-id");
            when(track.makeClone()).thenReturn(clone);
            return track;
        }

        private AudioTrack enqueue(AudioTrack track)
        {
            QueuedTrack queuedTrack = mock(QueuedTrack.class);
            when(queuedTrack.getTrack()).thenReturn(track);
            when(queuedTrack.getIdentifier()).thenReturn(0L);
            audioHandler.getQueue().add(queuedTrack);
            return track;
        }

        private Runnable captureScheduledReconnect(long expectedDelaySeconds)
        {
            ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(threadpool).schedule(captor.capture(), eq(expectedDelaySeconds), eq(TimeUnit.SECONDS));
            return captor.getValue();
        }

        private void verifyNoReconnectScheduled()
        {
            verify(threadpool, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("persistence disabled: dropped stream ends the queue as before")
        void disabled_droppedStreamEndsQueue()
        {
            when(bot.getNowplayingHandler()).thenReturn(mock(NowPlayingHandler.class));
            AudioTrack stream = createStreamTrack();

            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.FINISHED);

            verifyNoReconnectScheduled();
            verify(bot).closeAudioConnection(GUILD_ID);
            assertFalse(audioHandler.isStreamReconnectPending());
        }

        @Test
        @DisplayName("FINISHED stream schedules a reconnect instead of leaving the channel")
        void finishedStream_schedulesReconnect()
        {
            enablePersistence(0);
            AudioTrack stream = createStreamTrack();

            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.FINISHED);

            Runnable reconnect = captureScheduledReconnect(5);
            verify(bot, never()).closeAudioConnection(anyLong());
            assertTrue(audioHandler.getQueue().isEmpty());
            assertTrue(audioHandler.isStreamReconnectPending());

            when(audioPlayer.getPlayingTrack()).thenReturn(null);
            reconnect.run();

            verify(audioPlayer).playTrack(stream.makeClone());
            assertFalse(audioHandler.isStreamReconnectPending());
        }

        @Test
        @DisplayName("LOAD_FAILED stream reconnects with a growing delay")
        void loadFailed_backsOff()
        {
            enablePersistence(0);
            AudioTrack stream = createStreamTrack();

            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.LOAD_FAILED);
            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.LOAD_FAILED);
            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.LOAD_FAILED);

            verify(threadpool).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
            verify(threadpool).schedule(any(Runnable.class), eq(10L), eq(TimeUnit.SECONDS));
            verify(threadpool).schedule(any(Runnable.class), eq(20L), eq(TimeUnit.SECONDS));
            verify(bot, never()).closeAudioConnection(anyLong());
        }

        @Test
        @DisplayName("user skip (STOPPED) of a stream plays the next track and does not reconnect")
        void userStop_doesNotReconnect()
        {
            enablePersistence(0);
            AudioTrack stream = createStreamTrack();
            AudioTrack next = enqueue(createRegularTrack());

            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.STOPPED);

            verifyNoReconnectScheduled();
            verify(audioPlayer).playTrack(next);
        }

        @Test
        @DisplayName("REPLACED stream does not reconnect")
        void replaced_doesNotReconnect()
        {
            enablePersistence(0);
            AudioTrack stream = createStreamTrack();
            enqueue(createRegularTrack());

            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.REPLACED);

            verifyNoReconnectScheduled();
        }

        @Test
        @DisplayName("stalled stream is stopped and then reconnected")
        void stalledStream_isRestarted()
        {
            enablePersistence(0);
            AudioTrack stream = createStreamTrack();

            audioHandler.onTrackStuck(audioPlayer, stream, 10_000L);
            verify(audioPlayer).stopTrack();

            // Lavaplayer fires the end event for the stop we just issued.
            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.STOPPED);

            captureScheduledReconnect(5);
            verify(bot, never()).closeAudioConnection(anyLong());
        }

        @Test
        @DisplayName("stall of a regular track is not restarted")
        void stalledRegularTrack_notRestarted()
        {
            enablePersistence(0);
            AudioTrack song = createRegularTrack();

            audioHandler.onTrackStuck(audioPlayer, song, 10_000L);

            verify(audioPlayer, never()).stopTrack();
        }

        @Test
        @DisplayName("reconnect is skipped when another track started playing meanwhile")
        void reconnect_skippedWhenSomethingElsePlaying()
        {
            enablePersistence(0);
            AudioTrack stream = createStreamTrack();
            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.FINISHED);
            Runnable reconnect = captureScheduledReconnect(5);

            when(audioPlayer.getPlayingTrack()).thenReturn(mock(AudioTrack.class));
            reconnect.run();

            verify(audioPlayer, never()).playTrack(any());
        }

        @Test
        @DisplayName("stopAndClear() cancels a pending reconnect")
        void stopAndClear_cancelsPendingReconnect()
        {
            enablePersistence(0);
            AudioTrack stream = createStreamTrack();
            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.FINISHED);
            Runnable reconnect = captureScheduledReconnect(5);
            assertTrue(audioHandler.isStreamReconnectPending());

            audioHandler.stopAndClear();

            verify(scheduledFuture).cancel(false);
            assertFalse(audioHandler.isStreamReconnectPending());

            // Even if the task still fires, it must not resurrect the stream.
            when(audioPlayer.getPlayingTrack()).thenReturn(null);
            reconnect.run();
            verify(audioPlayer, never()).playTrack(any());
        }

        @Test
        @DisplayName("stopAndClearQueuePreserveHistory() cancels a pending reconnect")
        void stopAndClearPreserveHistory_cancelsPendingReconnect()
        {
            enablePersistence(0);
            AudioTrack stream = createStreamTrack();
            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.FINISHED);
            Runnable reconnect = captureScheduledReconnect(5);

            audioHandler.stopAndClearQueuePreserveHistory();

            verify(scheduledFuture).cancel(false);
            when(audioPlayer.getPlayingTrack()).thenReturn(null);
            reconnect.run();
            verify(audioPlayer, never()).playTrack(any());
        }

        @Test
        @DisplayName("gives up after the configured attempt limit and ends the queue normally")
        void attemptLimit_reached_endsQueue()
        {
            enablePersistence(2);
            AudioTrack stream = createStreamTrack();

            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.LOAD_FAILED);
            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.LOAD_FAILED);
            verify(bot, never()).closeAudioConnection(anyLong());

            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.LOAD_FAILED);

            verify(threadpool, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
            verify(bot).closeAudioConnection(GUILD_ID);
        }

        @Test
        @DisplayName("regular (non-stream) track that finishes is never reconnected")
        void regularTrack_notReconnected()
        {
            enablePersistence(0);
            AudioTrack song = createRegularTrack();
            AudioTrack next = enqueue(createRegularTrack());

            audioHandler.onTrackEnd(audioPlayer, song, AudioTrackEndReason.FINISHED);

            verifyNoReconnectScheduled();
            verify(audioPlayer).playTrack(next);
        }

        @Test
        @DisplayName("zero delay reconnects immediately without the scheduler")
        void zeroDelay_reconnectsInline()
        {
            when(config.persistStreams()).thenReturn(true);
            when(config.getStreamReconnectDelaySeconds()).thenReturn(0L);
            when(config.getStreamReconnectMaxDelaySeconds()).thenReturn(0L);
            when(config.getStreamReconnectMaxAttempts()).thenReturn(0);
            audioHandler = newHandler();
            AudioTrack stream = createStreamTrack();
            when(audioPlayer.getPlayingTrack()).thenReturn(null);

            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.FINISHED);

            verifyNoReconnectScheduled();
            verify(audioPlayer).playTrack(stream.makeClone());
        }

        @Test
        @DisplayName("starting a live stream records it for resume-on-restart with the connected channel")
        void trackStart_recordsStreamForResume()
        {
            when(bot.getNowplayingHandler()).thenReturn(mock(NowPlayingHandler.class));
            when(jda.getGuildById(GUILD_ID)).thenReturn(guild);
            when(audioManager.getConnectedChannel()).thenReturn(audioChannel);
            when(audioChannel.getIdLong()).thenReturn(42L);
            AudioTrack stream = createStreamTrack();

            audioHandler.onTrackStart(audioPlayer, stream);

            verify(streamResumeStore).record(GUILD_ID, 42L, STREAM_URI, "Station");
        }

        @Test
        @DisplayName("starting a live stream before voice is connected records channel 0")
        void trackStart_recordsStreamWithoutChannel()
        {
            when(bot.getNowplayingHandler()).thenReturn(mock(NowPlayingHandler.class));
            AudioTrack stream = createStreamTrack();

            audioHandler.onTrackStart(audioPlayer, stream);

            verify(streamResumeStore).record(GUILD_ID, 0L, STREAM_URI, "Station");
        }

        @Test
        @DisplayName("starting a regular track forgets the resume entry")
        void trackStart_regularTrackClearsResume()
        {
            when(bot.getNowplayingHandler()).thenReturn(mock(NowPlayingHandler.class));

            audioHandler.onTrackStart(audioPlayer, createRegularTrack());

            verify(streamResumeStore).clear(GUILD_ID);
            verify(streamResumeStore, never()).record(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("stopping playback forgets the resume entry")
        void stop_clearsResume()
        {
            audioHandler.stopAndClear();
            verify(streamResumeStore).clear(GUILD_ID);

            audioHandler.stopAndClearQueuePreserveHistory();
            verify(streamResumeStore, times(2)).clear(GUILD_ID);
        }

        @Test
        @DisplayName("queue running out forgets the resume entry")
        void queueEnd_clearsResume()
        {
            when(bot.getNowplayingHandler()).thenReturn(mock(NowPlayingHandler.class));

            audioHandler.onTrackEnd(audioPlayer, createRegularTrack(), AudioTrackEndReason.FINISHED);

            verify(streamResumeStore).clear(GUILD_ID);
        }

        @Test
        @DisplayName("starting a live stream starts station metadata polling; ending it stops polling")
        void streamMetadata_startAndStop()
        {
            when(bot.getNowplayingHandler()).thenReturn(mock(NowPlayingHandler.class));
            AudioTrack stream = createStreamTrack();

            audioHandler.onTrackStart(audioPlayer, stream);
            verify(streamMetadataService).start(eq(GUILD_ID), eq(stream), any());

            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.FINISHED);
            verify(streamMetadataService).stop(GUILD_ID);
        }

        @Test
        @DisplayName("regular tracks never start station metadata polling")
        void streamMetadata_notForRegularTracks()
        {
            when(bot.getNowplayingHandler()).thenReturn(mock(NowPlayingHandler.class));

            audioHandler.onTrackStart(audioPlayer, createRegularTrack());

            verify(streamMetadataService, never()).start(anyLong(), any(), any());
        }

        @Test
        @DisplayName("metadata change refreshes the now-playing message through the handler")
        void streamMetadata_listenerRefreshesNowPlaying()
        {
            NowPlayingHandler np = mock(NowPlayingHandler.class);
            when(bot.getNowplayingHandler()).thenReturn(np);
            AudioTrack stream = createStreamTrack();
            audioHandler.onTrackStart(audioPlayer, stream);
            ArgumentCaptor<StreamMetadataService.Listener> captor = ArgumentCaptor.forClass(StreamMetadataService.Listener.class);
            verify(streamMetadataService).start(eq(GUILD_ID), eq(stream), captor.capture());
            StreamMetadata md = new StreamMetadata("S", "T", "A", null, 1, java.time.Instant.now());

            captor.getValue().onMetadataChanged(GUILD_ID, stream, md);

            verify(np).onStreamMetadataUpdate(GUILD_ID, stream, md);
        }

        @Test
        @DisplayName("disabled resume store is never written")
        void disabledStore_notWritten()
        {
            when(streamResumeStore.isEnabled()).thenReturn(false);
            when(bot.getNowplayingHandler()).thenReturn(mock(NowPlayingHandler.class));

            audioHandler.onTrackStart(audioPlayer, createStreamTrack());

            verify(streamResumeStore, never()).record(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("attempt counter resets after the stream has played stably")
        void attemptCounter_resetsAfterStablePlayback()
        {
            enablePersistence(1);
            AudioTrack stream = createStreamTrack();

            // First drop uses the single allowed attempt.
            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.LOAD_FAILED);
            verify(threadpool, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

            // The reconnected stream plays for a while: frames flow and position passes the threshold.
            AudioTrack playing = createStreamTrack();
            when(playing.getPosition()).thenReturn(AudioHandler.STREAM_STABLE_PLAYBACK_MS + 1);
            when(audioPlayer.getPlayingTrack()).thenReturn(playing);
            when(audioPlayer.provide()).thenReturn(mock(com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame.class));
            assertTrue(audioHandler.canProvide());

            // A later drop is allowed one attempt again rather than giving up.
            audioHandler.onTrackEnd(audioPlayer, stream, AudioTrackEndReason.LOAD_FAILED);
            verify(threadpool, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
            verify(bot, never()).closeAudioConnection(anyLong());
        }
    }
}
