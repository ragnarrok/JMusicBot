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

import com.jagrosh.jmusicbot.audio.StreamReconnectPolicy;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("StreamReconnectPolicy Tests")
class StreamReconnectPolicyTest
{
    private static AudioTrack trackWithInfo(AudioTrackInfo info)
    {
        AudioTrack track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(info);
        return track;
    }

    @Nested
    @DisplayName("Live stream detection")
    class LiveStreamDetection
    {
        @Test
        @DisplayName("track flagged as stream is a live stream")
        void flaggedStream()
        {
            AudioTrack track = trackWithInfo(new AudioTrackInfo("Radio", "Station", Units.DURATION_MS_UNKNOWN, "id", true, "https://radio.example/stream"));
            assertTrue(StreamReconnectPolicy.isLiveStream(track));
        }

        @Test
        @DisplayName("track with unknown duration is a live stream even if not flagged")
        void unknownDuration()
        {
            AudioTrack track = trackWithInfo(new AudioTrackInfo("Radio", "Station", Units.DURATION_MS_UNKNOWN, "id", false, "https://radio.example/stream"));
            assertTrue(StreamReconnectPolicy.isLiveStream(track));
        }

        @Test
        @DisplayName("regular track with a duration is not a live stream")
        void regularTrack()
        {
            AudioTrack track = trackWithInfo(new AudioTrackInfo("Song", "Artist", 180_000, "id", false, "https://example/song"));
            assertFalse(StreamReconnectPolicy.isLiveStream(track));
        }

        @Test
        @DisplayName("null track or missing info is not a live stream")
        void nullTrack()
        {
            assertFalse(StreamReconnectPolicy.isLiveStream(null));
            assertFalse(StreamReconnectPolicy.isLiveStream(trackWithInfo(null)));
        }
    }

    @Nested
    @DisplayName("Unexpected end detection")
    class UnexpectedEndDetection
    {
        private final StreamReconnectPolicy policy = new StreamReconnectPolicy(true, 5, 60, 0);

        @Test
        @DisplayName("FINISHED and LOAD_FAILED are unexpected for a live stream")
        void finishedAndLoadFailed()
        {
            assertTrue(policy.isUnexpectedEnd(AudioTrackEndReason.FINISHED, false));
            assertTrue(policy.isUnexpectedEnd(AudioTrackEndReason.LOAD_FAILED, false));
        }

        @Test
        @DisplayName("STOPPED is unexpected only when the handler requested a restart")
        void stopped()
        {
            assertFalse(policy.isUnexpectedEnd(AudioTrackEndReason.STOPPED, false));
            assertTrue(policy.isUnexpectedEnd(AudioTrackEndReason.STOPPED, true));
        }

        @Test
        @DisplayName("REPLACED, CLEANUP and null are never unexpected")
        void replacedCleanupNull()
        {
            assertFalse(policy.isUnexpectedEnd(AudioTrackEndReason.REPLACED, true));
            assertFalse(policy.isUnexpectedEnd(AudioTrackEndReason.CLEANUP, true));
            assertFalse(policy.isUnexpectedEnd(null, true));
        }
    }

    @Nested
    @DisplayName("Backoff and attempt limits")
    class Backoff
    {
        @Test
        @DisplayName("delay doubles per attempt and is capped at the max delay")
        void exponentialBackoffCapped()
        {
            StreamReconnectPolicy policy = new StreamReconnectPolicy(true, 5, 60, 0);

            assertEquals(5, policy.nextDelaySeconds());
            assertEquals(10, policy.nextDelaySeconds());
            assertEquals(20, policy.nextDelaySeconds());
            assertEquals(40, policy.nextDelaySeconds());
            assertEquals(60, policy.nextDelaySeconds());
            assertEquals(60, policy.nextDelaySeconds());
            assertEquals(6, policy.getAttempts());
        }

        @Test
        @DisplayName("unlimited attempts never return -1")
        void unlimitedAttempts()
        {
            StreamReconnectPolicy policy = new StreamReconnectPolicy(true, 1, 1, 0);
            for (int i = 0; i < 100; i++)
                assertTrue(policy.nextDelaySeconds() >= 0);
        }

        @Test
        @DisplayName("returns -1 once the attempt limit is reached, and reset() restores it")
        void attemptLimit()
        {
            StreamReconnectPolicy policy = new StreamReconnectPolicy(true, 5, 60, 2);

            assertEquals(5, policy.nextDelaySeconds());
            assertEquals(10, policy.nextDelaySeconds());
            assertEquals(-1, policy.nextDelaySeconds());
            assertEquals(2, policy.getAttempts());

            policy.reset();
            assertEquals(0, policy.getAttempts());
            assertEquals(5, policy.nextDelaySeconds());
        }

        @Test
        @DisplayName("zero base delay reconnects immediately")
        void zeroDelay()
        {
            StreamReconnectPolicy policy = new StreamReconnectPolicy(true, 0, 60, 0);
            assertEquals(0, policy.nextDelaySeconds());
            assertEquals(0, policy.nextDelaySeconds());
        }

        @Test
        @DisplayName("max delay smaller than base delay is raised to the base delay")
        void maxDelayBelowBase()
        {
            StreamReconnectPolicy policy = new StreamReconnectPolicy(true, 10, 1, 0);
            assertEquals(10, policy.nextDelaySeconds());
            assertEquals(10, policy.nextDelaySeconds());
        }

        @Test
        @DisplayName("very large attempt counts do not overflow")
        void noOverflow()
        {
            StreamReconnectPolicy policy = new StreamReconnectPolicy(true, 5, 60, 0);
            for (int i = 0; i < 200; i++)
                assertEquals(Math.min(60L, 5L << Math.min(i, 20)), policy.nextDelaySeconds());
        }
    }

    @Test
    @DisplayName("disabled() policy reports disabled")
    void disabledPolicy()
    {
        assertFalse(StreamReconnectPolicy.disabled().isEnabled());
        assertTrue(new StreamReconnectPolicy(true, 5, 60, 0).isEnabled());
    }
}
