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

import com.jagrosh.jmusicbot.TestBase;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.StreamResumeStore;
import com.jagrosh.jmusicbot.audio.StreamResumer;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("StreamResumer Tests")
class StreamResumerTest extends TestBase
{
    private static final String URI = "https://radio.example.com/listen/station/radio.mp3";
    private static final long CHANNEL_ID = 42L;

    private StreamResumer resumer;
    private StreamResumeStore.Entry entry;

    @BeforeEach
    @Override
    public void setUp()
    {
        super.setUp();
        resumer = new StreamResumer(bot);
        entry = new StreamResumeStore.Entry(GUILD_ID, CHANNEL_ID, URI, "Station");

        when(streamResumeStore.entries()).thenReturn(List.of(entry));
        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);
        when(guild.getName()).thenReturn("Guild");
        when(guild.getChannelById(AudioChannel.class, CHANNEL_ID)).thenReturn(audioChannel);
        when(audioChannel.getName()).thenReturn("Radio");
        when(playerManager.setUpHandler(guild)).thenReturn(audioHandler);
        when(audioPlayer.getPlayingTrack()).thenReturn(null);
        when(settingsManager.getSettings(guild)).thenReturn(settings);
    }

    private AudioLoadResultHandler captureLoad()
    {
        ArgumentCaptor<AudioLoadResultHandler> captor = ArgumentCaptor.forClass(AudioLoadResultHandler.class);
        verify(playerManager).loadItemOrdered(eq(guild), eq(URI), captor.capture());
        return captor.getValue();
    }

    private AudioTrack streamTrack()
    {
        AudioTrack track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(new AudioTrackInfo("Station", "Azuracast", Units.DURATION_MS_UNKNOWN, "id", true, URI));
        return track;
    }

    @Test
    @DisplayName("loads the remembered stream, queues it and rejoins the voice channel")
    void resumesStream()
    {
        resumer.resumeAll(jda);
        AudioLoadResultHandler load = captureLoad();

        load.trackLoaded(streamTrack());

        verify(audioHandler).setLastReason("Resumed after restart.");
        verify(audioHandler).addTrack(any(QueuedTrack.class));
        verify(audioManager).openAudioConnection(audioChannel);
        verify(streamResumeStore, never()).clear(anyLong());
    }

    @Test
    @DisplayName("a playlist result resumes its selected or first track")
    void playlistUsesFirstTrack()
    {
        resumer.resumeAll(jda);
        AudioLoadResultHandler load = captureLoad();
        AudioPlaylist playlist = mock(AudioPlaylist.class);
        AudioTrack first = streamTrack();
        when(playlist.getSelectedTrack()).thenReturn(null);
        when(playlist.getTracks()).thenReturn(List.of(first));

        load.playlistLoaded(playlist);

        verify(audioHandler).addTrack(any(QueuedTrack.class));
        verify(audioManager).openAudioConnection(audioChannel);
    }

    @Test
    @DisplayName("no matches forgets the stream")
    void noMatchesClears()
    {
        resumer.resumeAll(jda);
        captureLoad().noMatches();

        verify(streamResumeStore).clear(GUILD_ID);
        verify(audioHandler, never()).addTrack(any());
    }

    @Test
    @DisplayName("load failure schedules a retry and keeps the entry")
    void loadFailedRetries()
    {
        resumer.resumeAll(jda);
        captureLoad().loadFailed(new FriendlyException("down", FriendlyException.Severity.COMMON, null));

        verify(threadpool).schedule(any(Runnable.class), eq(StreamResumer.RETRY_DELAY_SECONDS), eq(TimeUnit.SECONDS));
        verify(streamResumeStore, never()).clear(anyLong());
    }

    @Test
    @DisplayName("gives up after the last attempt but keeps the entry for the next restart")
    void givesUpAfterMaxAttempts()
    {
        resumer.resume(jda, entry, StreamResumer.MAX_LOAD_ATTEMPTS);
        captureLoad().loadFailed(new FriendlyException("down", FriendlyException.Severity.COMMON, null));

        verify(threadpool, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        verify(streamResumeStore, never()).clear(anyLong());
    }

    @Test
    @DisplayName("guild the bot left is forgotten without loading")
    void missingGuildClears()
    {
        when(jda.getGuildById(GUILD_ID)).thenReturn(null);

        resumer.resumeAll(jda);

        verify(streamResumeStore).clear(GUILD_ID);
        verify(playerManager, never()).loadItemOrdered(any(), anyString(), any(AudioLoadResultHandler.class));
    }

    @Test
    @DisplayName("missing voice channel with no default channel is forgotten")
    void missingChannelClears()
    {
        when(guild.getChannelById(AudioChannel.class, CHANNEL_ID)).thenReturn(null);
        when(settings.getVoiceChannel(guild)).thenReturn(null);

        resumer.resumeAll(jda);

        verify(streamResumeStore).clear(GUILD_ID);
        verify(playerManager, never()).loadItemOrdered(any(), anyString(), any(AudioLoadResultHandler.class));
    }

    @Test
    @DisplayName("falls back to the guild's default voice channel")
    void fallsBackToDefaultChannel()
    {
        VoiceChannel fallback = mock(VoiceChannel.class);
        when(fallback.getName()).thenReturn("Fallback");
        when(guild.getChannelById(AudioChannel.class, CHANNEL_ID)).thenReturn(null);
        when(settings.getVoiceChannel(guild)).thenReturn(fallback);

        resumer.resumeAll(jda);
        captureLoad().trackLoaded(streamTrack());

        verify(audioManager).openAudioConnection(fallback);
    }

    @Test
    @DisplayName("does nothing when something is already playing")
    void skipsWhenAlreadyPlaying()
    {
        when(audioPlayer.getPlayingTrack()).thenReturn(audioTrack);

        resumer.resumeAll(jda);

        verify(playerManager, never()).loadItemOrdered(any(), anyString(), any(AudioLoadResultHandler.class));
        verify(streamResumeStore, never()).clear(anyLong());
    }

    @Test
    @DisplayName("does not start if something began playing while the stream was loading")
    void skipsWhenPlaybackStartedMeanwhile()
    {
        resumer.resumeAll(jda);
        AudioLoadResultHandler load = captureLoad();
        when(audioPlayer.getPlayingTrack()).thenReturn(audioTrack);

        load.trackLoaded(streamTrack());

        verify(audioHandler, never()).addTrack(any());
        verify(audioManager, never()).openAudioConnection(any(AudioChannel.class));
    }

    @Test
    @DisplayName("disabled store is left alone")
    void disabledStoreDoesNothing()
    {
        when(streamResumeStore.isEnabled()).thenReturn(false);

        resumer.resumeAll(jda);

        verify(jda, never()).getGuildById(anyLong());
    }
}
