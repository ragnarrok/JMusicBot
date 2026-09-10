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
import com.jagrosh.jmusicbot.audio.VoiceRejoinHandler;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("VoiceRejoinHandler Tests")
class VoiceRejoinHandlerTest extends TestBase
{
    private static final long SELF_ID = 999L;
    private static final long CHANNEL_ID = 42L;

    private VoiceRejoinHandler handler;
    private SelfUser selfUser;

    @BeforeEach
    @Override
    public void setUp()
    {
        super.setUp();
        when(config.rejoinVoiceOnDisconnect()).thenReturn(true);
        when(config.getVoiceRejoinDelaySeconds()).thenReturn(5L);
        when(config.getVoiceRejoinMaxAttempts()).thenReturn(2);

        selfUser = mock(SelfUser.class);
        when(selfUser.getIdLong()).thenReturn(SELF_ID);
        when(jda.getSelfUser()).thenReturn(selfUser);
        when(member.getIdLong()).thenReturn(SELF_ID);
        when(guild.getName()).thenReturn("Guild");
        when(guild.getChannelById(AudioChannel.class, CHANNEL_ID)).thenReturn(audioChannel);
        when(audioChannel.getIdLong()).thenReturn(CHANNEL_ID);
        when(audioChannel.getName()).thenReturn("Radio");
        when(audioPlayer.getPlayingTrack()).thenReturn(audioTrack);
        when(audioManager.getConnectionStatus()).thenReturn(ConnectionStatus.NOT_CONNECTED);
        when(settingsManager.getSettings(guild)).thenReturn(settings);

        handler = new VoiceRejoinHandler(bot);
    }

    private GuildVoiceUpdateEvent event(Member who, AudioChannelUnion left, AudioChannelUnion joined)
    {
        GuildVoiceUpdateEvent event = mock(GuildVoiceUpdateEvent.class);
        when(event.getMember()).thenReturn(who);
        when(event.getJDA()).thenReturn(jda);
        when(event.getGuild()).thenReturn(guild);
        when(event.getChannelLeft()).thenReturn(left);
        when(event.getChannelJoined()).thenReturn(joined);
        return event;
    }

    private Runnable captureRejoin()
    {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(threadpool).schedule(captor.capture(), eq(5L), eq(TimeUnit.SECONDS));
        return captor.getValue();
    }

    private void verifyNoRejoinScheduled()
    {
        verify(threadpool, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("bot disconnected while playing rejoins the channel it left after the delay")
    void disconnectedWhilePlaying_rejoins()
    {
        handler.onVoiceUpdate(event(member, audioChannel, null));

        Runnable rejoin = captureRejoin();
        verify(audioManager, never()).openAudioConnection(any(AudioChannel.class));

        rejoin.run();

        verify(audioManager).openAudioConnection(audioChannel);
        assertEquals(1, handler.getAttempts(GUILD_ID));
    }

    @Test
    @DisplayName("another member leaving is ignored")
    void otherMemberIgnored()
    {
        Member someoneElse = mock(Member.class);
        when(someoneElse.getIdLong()).thenReturn(1L);

        handler.onVoiceUpdate(event(someoneElse, audioChannel, null));

        verifyNoRejoinScheduled();
    }

    @Test
    @DisplayName("disconnect with nothing playing is a deliberate leave")
    void nothingPlaying_noRejoin()
    {
        when(audioPlayer.getPlayingTrack()).thenReturn(null);

        handler.onVoiceUpdate(event(member, audioChannel, null));

        verifyNoRejoinScheduled();
    }

    @Test
    @DisplayName("a live stream waiting to reconnect counts as playing")
    void pendingStreamReconnect_countsAsPlaying()
    {
        when(audioPlayer.getPlayingTrack()).thenReturn(null);
        when(audioHandler.isStreamReconnectPending()).thenReturn(true);

        handler.onVoiceUpdate(event(member, audioChannel, null));

        captureRejoin();
    }

    @Test
    @DisplayName("disabled handler never schedules")
    void disabled_noRejoin()
    {
        when(config.rejoinVoiceOnDisconnect()).thenReturn(false);
        handler = new VoiceRejoinHandler(bot);

        handler.onVoiceUpdate(event(member, audioChannel, null));
        handler.watchdog();

        assertFalse(handler.isEnabled());
        verifyNoRejoinScheduled();
    }

    @Test
    @DisplayName("rejoin is skipped if playback stopped or the connection came back meanwhile")
    void rejoinSkippedWhenStateChanged()
    {
        handler.onVoiceUpdate(event(member, audioChannel, null));
        Runnable rejoin = captureRejoin();

        when(audioManager.getConnectionStatus()).thenReturn(ConnectionStatus.CONNECTED);
        rejoin.run();
        verify(audioManager, never()).openAudioConnection(any(AudioChannel.class));

        when(audioManager.getConnectionStatus()).thenReturn(ConnectionStatus.NOT_CONNECTED);
        when(audioPlayer.getPlayingTrack()).thenReturn(null);
        rejoin.run();
        verify(audioManager, never()).openAudioConnection(any(AudioChannel.class));
    }

    @Test
    @DisplayName("falls back to the default voice channel when the left channel is gone")
    void fallsBackToDefaultChannel()
    {
        VoiceChannel fallback = mock(VoiceChannel.class);
        when(fallback.getName()).thenReturn("Fallback");
        when(guild.getChannelById(AudioChannel.class, CHANNEL_ID)).thenReturn(null);
        when(settings.getVoiceChannel(guild)).thenReturn(fallback);

        handler.onVoiceUpdate(event(member, audioChannel, null));
        captureRejoin().run();

        verify(audioManager).openAudioConnection(fallback);
    }

    @Test
    @DisplayName("a second disconnect while a rejoin is pending does not schedule twice")
    void pendingNotDuplicated()
    {
        handler.onVoiceUpdate(event(member, audioChannel, null));
        handler.onVoiceUpdate(event(member, audioChannel, null));

        verify(threadpool, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("stops after the attempt limit within the window")
    void attemptLimit()
    {
        for (int i = 0; i < 3; i++)
        {
            handler.onVoiceUpdate(event(member, audioChannel, null));
            ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(threadpool, atMost(2)).schedule(captor.capture(), anyLong(), any(TimeUnit.class));
            captor.getAllValues().forEach(Runnable::run); // clears the pending flag
        }

        verify(threadpool, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        assertEquals(2, handler.getAttempts(GUILD_ID));
    }

    @Test
    @DisplayName("watchdog rejoins the last known channel when playing but not connected")
    void watchdogRejoins()
    {
        handler.onVoiceUpdate(event(member, null, audioChannel)); // remembers the channel on join
        SelfMember self = mock(SelfMember.class);
        GuildVoiceState voiceState = mock(GuildVoiceState.class);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.getVoiceState()).thenReturn(voiceState);
        when(voiceState.getChannel()).thenReturn(null);
        when(jda.getGuilds()).thenReturn(List.of(guild));

        handler.watchdog();
        captureRejoin().run();

        verify(audioManager).openAudioConnection(audioChannel);
    }

    @Test
    @DisplayName("watchdog leaves connecting or connected guilds alone")
    void watchdogIgnoresConnecting()
    {
        when(jda.getGuilds()).thenReturn(List.of(guild));
        when(audioManager.getConnectionStatus()).thenReturn(ConnectionStatus.CONNECTING_AWAITING_ENDPOINT);
        handler.watchdog();
        when(audioManager.getConnectionStatus()).thenReturn(ConnectionStatus.CONNECTED);
        handler.watchdog();

        verifyNoRejoinScheduled();
    }

    @Test
    @DisplayName("init() schedules the watchdog only when enabled")
    void initSchedulesWatchdog()
    {
        handler.init();
        verify(threadpool).scheduleWithFixedDelay(any(Runnable.class), eq(VoiceRejoinHandler.WATCHDOG_INTERVAL_SECONDS),
                eq(VoiceRejoinHandler.WATCHDOG_INTERVAL_SECONDS), eq(TimeUnit.SECONDS));

        when(config.rejoinVoiceOnDisconnect()).thenReturn(false);
        new VoiceRejoinHandler(bot).init();
        verify(threadpool, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    }
}
